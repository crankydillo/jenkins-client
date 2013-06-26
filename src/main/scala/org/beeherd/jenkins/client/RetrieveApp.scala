package org.beeherd.jenkins.client

import java.io._
import java.text.DecimalFormat
import java.util.zip.{
  ZipEntry, ZipFile, ZipInputStream
}

import javax.script._

import org.apache.commons.cli.{
  BasicParser, OptionGroup => OptGroup,
  Option => Opt, Options => Opts, OptionBuilder => OptBuilder, ParseException, 
  HelpFormatter, CommandLine
}
import org.apache.commons.io.{
  FileUtils, IOUtils
}

import org.apache.http.{
  HttpHost, HttpRequestInterceptor
}
import org.apache.http.client.{
  HttpClient => ApacheHttpClient
}
import org.apache.http.client.{
  CredentialsProvider, ResponseHandler, 
  HttpClient => ApacheHttpClient, HttpResponseException
}
import org.apache.http.client.methods.{
  HttpGet, HttpPost, HttpPut, HttpDelete
}
import org.apache.http.client.params.AuthPolicy
import org.apache.http.client.protocol.ClientContext
import org.apache.http.conn.scheme.{
  Scheme, SchemeRegistry, PlainSocketFactory
}
import org.apache.http.auth.{
  AuthScope, AuthScheme, AuthState, UsernamePasswordCredentials
}
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.protocol.{
  BasicHttpContext, ExecutionContext, HttpContext
}
import org.apache.http.params.{
  BasicHttpParams, HttpConnectionParams
}
import org.apache.http.impl.client.{
  BasicAuthCache, BasicResponseHandler, AbstractHttpClient,
  DefaultHttpClient, DefaultHttpRequestRetryHandler
}
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager


import org.springframework.context.support.ClassPathXmlApplicationContext

import org.beeherd.archive.Archive
import org.beeherd.client._
//import org.beeherd.http.client._
import org.beeherd.client.http._

/**
* Basically a script tieing together a bunch of tools in order to grab
* something from Jenkins and do optional stuff with it.  Stuff includes things
* like exploding into a directory, running pre and post processing code, etc.
*
* @author scox
*/
object RetrieveApp {
  def main(args: Array[String]): Unit = {

    val opts = new Opts();

    def addRequired(name: String, desc: String): Unit = {
      val opt = new Opt(name, true, desc);
      opt.setRequired(true);
      opts.addOption(opt);
    }

    addRequired("s", "Host");
    addRequired("p", "Port");
    addRequired("mp", "Path to the module");
    addRequired("rp", "Relative path for the artifact");
    addRequired("t", "Download file's type (e.g. zip, tar.gz)");

    var opt = new Opt("b", true, "The directory that contains the builds");
    opt.setRequired(false);
    opts.addOption(opt)

    opt = new Opt("e", false, "explode the downloaded file");
    opt.setRequired(false);
    opts.addOption(opt)

    opt = new Opt("ls", true, "suffix to use that represents the last " +
      "exploded build (default: -last).");
    opt.setRequired(false);
    opts.addOption(opt)

    opt = new Opt("bn", true, "the build number to retrieve (default: lastStableBuild)");
    opt.setRequired(false);
    opts.addOption(opt)

    opt = new Opt("d", false, "if exploding, delete the zip file");
    opt.setRequired(false);
    opts.addOption(opt);

    opt = new Opt("prejs", true, "path to javascript file that will be run as " +
      "a first step when exploding the downloaded file.  Unlike -postjs, this " +
      "script will not execute UNLESS you are using the -e option");
    opt.setRequired(false);
    opts.addOption(opt)

    opt = new Opt("postjs", true, "path to javascript file that will be run last")
    opt.setRequired(false);
    opts.addOption(opt)

    val parser = new BasicParser();
    var response: Response = null;
    val client = ClientFactory.createClient; // TODO replace this
    var in: ZipInputStream = null;
    try {
      val cmd = parser.parse(opts, args);

      val baseDir = new File(cmd.getOptionValue("b"));

      // See if someone has created a compiled PreProcessor
      val appContext = 
        new ClassPathXmlApplicationContext("application-context.xml");

      appContext.getBean("preProcessor") match {
        case null => {}
        case pre: PreProcessor => pre.process(baseDir)
        case _ => { println("Injected prePocessor must be of type " +
          "org.beeherd.jenkins.client.PreProcessor") }
      }

      if (cmd.hasOption("prejs")) 
        executeJavascript(cmd.getOptionValue("prejs"), baseDir)


      val host = cmd.getOptionValue("s");
      val port = cmd.getOptionValue("p").toInt;
      val modPath = cmd.getOptionValue("mp");
      val relPath = cmd.getOptionValue("rp");
      val prefix = "jenkins-";
      val suffix = cmd.getOptionValue("ls", "-last");
      val downloadType = cmd.getOptionValue("t");

      val dispatcher = new HttpClient(client, true);

      val useLastStable = !cmd.hasOption("bn");

      val build = 
        if (!useLastStable) {
          cmd.getOptionValue("bn")
        } else {
          val modRetriever = new ModuleRetriever(dispatcher)
          val (resp, moduleInfoOpt) = modRetriever.retrieve(host, modPath + "/api/xml", port);
          moduleInfoOpt match {
            case Some(modInfo) => modInfo.lastStable match {
              case Some(b) => b.number;
              case None => throw new RuntimeException("No last stable build!")
            }
            case None => throw new RuntimeException("Unable to retrieve module information. " + resp)
          }
        }

      val path = modPath + "/" + build + "/" + relPath;
      val request = new HttpRequest(host, path, port = port, protocol = "https");

      val name = 
        if (useLastStable) prefix + build + suffix
        else prefix + build

      val file = new File(name + "." + downloadType);
      dispatcher.download(request, 30, file);

      val explodedDirOpt: Option[File] = 
        if (cmd.hasOption("e")) {
          if (!(baseDir.exists && baseDir.isDirectory)) {
            println("The -b parameter must specify an existing directory.");
            None
          } else {
            if (useLastStable)
              renameDirs(baseDir, suffix)
            val toDir = new File(baseDir, name);
            explode(file, toDir)
            if (cmd.hasOption("d")) {
              println("Deleting " + file.getAbsolutePath);
              if (!file.delete())
                println("There was a problem deleting " + file.getAbsolutePath + 
                " this directory.  You should manually delete it.");
            }
            Some(toDir)
          }
        } else {
          None
        }

      appContext.getBean("postProcessor") match {
        case null => {}
        case post: PostProcessor => post.process(baseDir, explodedDirOpt)
        case _ => { println("Injected postPocessor must be of type " +
          "org.beeherd.jenkins.client.PostProcessor") }
      }

      if (cmd.hasOption("postjs"))
        executeJavascript(cmd.getOptionValue("postjs"), file, explodedDirOpt);

    } catch {
      case pe: ParseException => {
        usage(opts)
        System.exit(1);
      }
      case e:ExplosionException => {
        println(e.getMessage);
        System.exit(1);
      }
      case e:Exception => {
        e.printStackTrace;
        println(response);
        System.exit(1);
      }
    } finally {
      if (in != null) try {in.close()} catch { case e:Exception => {}}
      client.getConnectionManager.shutdown();
    }
  }

  private def usage(opts: Opts): Unit = {
    val formatter = new HelpFormatter();
    formatter.printHelp("Jenkins Build Retriever", opts);
  }

  private def renameDirs(baseDir: File, suffix: String): Unit = {
    def suggestName(name: String, ctr: Int): String = {
      if (new File(baseDir, name + "." + ctr).exists)
        suggestName(name, ctr + 1)
      else
        name + "." + ctr
    }

    baseDir.listFiles
    .filter {_.getName.endsWith(suffix)}
    .foreach {f =>
      val name = f.getName;
      val newName = name.substring(0, name.lastIndexOf(suffix));
      val newFile = new File(baseDir, newName);
      if (newFile.exists) {
        val bakName = suggestName(newName, 1);
        println("Could not rename " + name + " to " + newName +
          " because a file with that name already exists.  Renaming to " + bakName)
        val res = f.renameTo(new File(baseDir, bakName));
        if (!res)
          println("Could not rename " + name + " to " + newName + ".");
      } else {
        val res = f.renameTo(newFile)
          if (!res)
          println("Could not rename " + name + " to " + newName + ".");
      }
    }
  }

  private def explode(file: File, toDir: File): Unit = {
    try {
      if (toDir.exists) 
        throw new RuntimeException(toDir.getAbsolutePath + " already exists, not exploding.");
      toDir.mkdir();
      println("Exploding the archive to " + toDir.getAbsolutePath);

      Archive.explode(file, toDir);

    } catch {
      case e:Exception => throw new ExplosionException(e.getMessage);
    }
  }

  private def executeJavascript(
    path: String
    , buildsDir: File
    , targetDir: Option[File] = None
  ): Unit = {
    val jsFile = new File(path);
    if (!jsFile.exists || jsFile.isDirectory)
      throw new RuntimeException("The Javascript file does not exist or is a directory.");
    val reader = new BufferedReader(new FileReader(jsFile));
    try {
      // TODO Make script engine configurable
      val engineName = "JavaScript";
      val jsEngine = new ScriptEngineManager().getEngineByName(engineName);
      if (jsEngine == null)
        throw new RuntimeException("The scripting engine, " + engineName + ", could not be found.");
      if (targetDir.isDefined)
        jsEngine.put("targetDir", targetDir.get)

      jsEngine.put("buildsDir", buildsDir);
      println("Executing the JavaScript file, " + jsFile.getAbsolutePath)
      jsEngine.eval(reader);
    } finally {
      try {reader.close()} catch { case e:Exception => {}}
    }
  }

  private class ExplosionException(msg: String) extends Exception(msg)

}

import javax.net.ssl.{
  SSLContext, X509TrustManager
}
import java.security.cert.X509Certificate
import org.apache.http.conn.ssl.SSLSocketFactory
object ClientFactory {
  private val Timeout = 30 * 1000;
  private val user = {
    print("Username: ");
    new jline.ConsoleReader().readLine()
  }

  private val password = {
    print("Password: ")
    new jline.ConsoleReader().readLine(new Character('*'))
  }

  def createClient: ApacheHttpClient = {
    val client = {

      /*
      val ctx = SSLContext.getInstance("TLS");

      val tm = new X509TrustManager() {
        def checkClientTrusted(xcs: Array[X509Certificate], string: String): Unit = {}
        def checkServerTrusted(xcs: Array[X509Certificate], string: String): Unit = {}
        def getAcceptedIssuers(): Array[X509Certificate] = null
      };

      ctx.init(null, Array(tm), null);
      val ssf = new SSLSocketFactory(ctx);
      val schemeRegistry = new SchemeRegistry();
      schemeRegistry.register( 
          new Scheme("https", PlainSocketFactory.getSocketFactory(), 443)
        );
      */

      val cm = new ThreadSafeClientConnManager();
      //val cm = new ThreadSafeClientConnManager(schemeRegistry);

      val params = new BasicHttpParams(); 
      HttpConnectionParams.setConnectionTimeout(params, Timeout);

      new DefaultHttpClient(cm, params)
    }

    client.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler(0, false));

    //client.getParams().setAuthenticationPreemptive(true);
    val authScope = new AuthScope(AuthScope.ANY); 
    val creds = new UsernamePasswordCredentials(user, password);
    client.getCredentialsProvider().setCredentials(authScope, creds);

    val params = new BasicHttpParams(); 
    HttpConnectionParams.setConnectionTimeout(params,  Timeout);
    HttpConnectionParams.setSoTimeout(params, Timeout);
    client.setParams(params);


    val localContext = new BasicHttpContext();
    val basicAuth = new BasicScheme();
    localContext.setAttribute("preemptive-auth", basicAuth);

    client.addRequestInterceptor(
        new HttpRequestInterceptor {
          def process(request: org.apache.http.HttpRequest, context: HttpContext): Unit = {
            val authState = context.getAttribute(ClientContext.TARGET_AUTH_STATE)
              .asInstanceOf[AuthState];

            if (authState.getAuthScheme() == null) {
              var authScheme = context.getAttribute("preemptive-auth").asInstanceOf[AuthScheme];
              if (authScheme == null) {
                context.setAttribute("preemptive-auth", basicAuth);
                authScheme = basicAuth;
              }
              val credsProvider = context.getAttribute(ClientContext.CREDS_PROVIDER)
                .asInstanceOf[CredentialsProvider];
              val targetHost = context.getAttribute(ExecutionContext.HTTP_TARGET_HOST)
                .asInstanceOf[HttpHost];
              if (authScheme != null) {
                val creds = credsProvider.getCredentials(
                    new AuthScope(targetHost.getHostName(), targetHost.getPort()));
                if (creds == null) {
                  throw new RuntimeException("No credentials for preemptive authentication");
                }
                authState.setAuthScheme(authScheme);
                authState.setCredentials(creds);
              } else {
                println("no authscheme:(");
              }
            }
          }
        }
        , 0
      );

    client
  }

}

package org.beeherd.jenkins.client

import java.io.{File, FileWriter, InputStream, Writer}
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.zip.{ZipEntry, ZipInputStream}

import org.apache.commons.cli.{BasicParser, OptionGroup => OptGroup,
Option => Opt, Options => Opts, OptionBuilder => OptBuilder, ParseException, 
HelpFormatter, CommandLine}
import org.apache.commons.io.IOUtils

import org.beeherd.client._
import org.beeherd.client.http._

object MetadataApp {
  def main(args: Array[String]): Unit = {

    val opts = new Opts();

    def addRequired(name: String, desc: String): Unit = {
      val opt = new Opt(name, true, desc);
      opt.setRequired(true);
      opts.addOption(opt);
    }

    addRequired("h", "host");
    addRequired("p", "port");
    addRequired("mp", "path to module");
    var opt = new Opt("sb", false, "show build numbers");
    opt.setRequired(false)
    opts.addOption(opt);

    opt = new Opt("b", true, "build for which you want metadata");
    opt.setRequired(false)
    opts.addOption(opt);

    val parser = new BasicParser();
    var response: Response = null;
    val client = ClientFactory.createClient; // TODO replace this
    try {
      val cmd = parser.parse(opts, args);

      val host = cmd.getOptionValue("h");
      val port = cmd.getOptionValue("p").toInt;
      val path = cmd.getOptionValue("mp");

      val dispatcher = new HttpClient(client, true);

      val modRetriever = new ModuleRetriever(dispatcher);
      val (resp, moduleInfoOpt) = modRetriever.retrieve(host, path, port);

      response = resp;

      moduleInfoOpt match {
        case Some(moduleInfo) => {
          if (cmd.hasOption("b")) {
            val buildNum = cmd.getOptionValue("b");
            moduleInfo.builds.find {_.number == buildNum} match {
              case Some(b) => {
                val path = b.url.replaceFirst("http://" + host + ":" + port, "") + "/api/xml";
                val request = new HttpRequest(host, path, port = port);
                response = dispatcher.submit(request);
                response match {
                  case XmlResponse(xml) => {
                    val buildInfo = Jenkins2BuildFull.unmarshal(b.number, b.url, xml);
                    print(buildInfo)
                  }
                  case r:Response => println("Invalid response: " + r);
                }
              }
              case None => println(buildNum + " is not a valid build number.")
            }
          } else {
            print(moduleInfo, cmd.hasOption("sb"))
          }
        }
        case _ => println("Invalid response: " + response)
      }
    } catch {
      case pe: ParseException => usage(opts)
      case e:Exception => {
        e.printStackTrace;
        println(response);
      }
    } finally {
      client.getConnectionManager.shutdown();
    }
  }

  private def usage(opts: Opts): Unit = {
    val formatter = new HelpFormatter();
    formatter.printHelp("Jenkins Build Information", opts);
  }

  private def print(module: Module, showBuilds: Boolean): Unit = {
    def opt2Str(build: Option[Build]) = build match {
      case Some(b) => b.number + "";
      case None => ""
    }
    println("Display Name: " + module.displayName);
    println("Name: " + module.name);
    println("Last Stable: " + opt2Str(module.lastStable));
    println("Last Completed: " + opt2Str(module.lastCompleted));
    println("Last Failed: " + opt2Str(module.lastFailed));
    println("Last Success: " + opt2Str(module.lastSuccess));
    println("Last Unsuccess: " + opt2Str(module.lastUnsuccess));
    if (showBuilds) {
      println();
      println("Builds");
      println("------");
      module.builds.foreach {b => println(b.number)}
    }
  }

  private def print(build: BuildFull): Unit = {
    println("Number: " + build.number);
    println("URL: " + build.url);
    val dateStr = {
      try {
        val ts = new Timestamp(build.timestamp.toLong);
        val sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        sdf.format(ts);
      } catch {
        case e:Exception => build.timestamp
      }
    }
    println("Build Date: " + dateStr);
    println()
    println("Changes");
    println("---------");
    build.changes.foreach {c => println(c.message + " (" + c.author + " - " + c.date + " " + c.time + ")")}
    println()
    println("Artifacts");
    println("---------");
    build.artifacts.foreach {println _}

  }
}

class ModuleRetriever(
  dispatcher: HttpClient
) {
  def retrieve(host: String, path: String, port: Int): (Response, Option[Module]) = {
    val request = new HttpRequest(host, path, port = port, protocol = "https");
    println(request.url);

    val response = dispatcher.submit(request);

    response match {
      case XmlResponse(xml) => (response, Some(Jenkins2Module.unmarshal(xml)))
      case _ => (response, None)
    }
  }
}

class Module(
  val name: String
  , val displayName: String
  , val builds: Seq[Build]
  , val lastStable: Option[Build]
  , val lastCompleted: Option[Build]
  , val lastFailed: Option[Build]
  , val lastSuccess: Option[Build]
  , val lastUnsuccess: Option[Build]
)

class Build(
  val number: String
  , val url: String
)

import scala.xml.{Elem, Node}
object XmlUtils {
  def text(parent: Node, name: String) = (parent \ name).text.trim
}

object Jenkins2Module {
  import XmlUtils.text

  def unmarshal(xml: Node): Module = {

    def builds = 
      (xml \ "build")
      .filter {_.isInstanceOf[Elem]}
      .map {b => new Build(text(b,"number"), text(b, "url"))}

    def build(name: String): Option[Build] = {
      try {
        val par = (xml \ name)(0);
        Some(new Build(text(par, "number"), text(par, "url")))
      } catch {
        case e:Exception => None
      }
    }
    new Module(
      text(xml, "name")
      , text(xml, "displayName")
      , builds
      , build("lastStableBuild")
      , build("lastCompletedBuild")
      , build("lastFailedBuild")
      , build("lastSuccessfulBuild")
      , build("lastUnsuccessfulBuild")
    )
  }
}

class BuildFull(
  val number: String
  , val url: String
  , val timestamp: String
  , val artifacts: Seq[String] = Nil
  , val changes: Seq[Change] = Nil
)

object Jenkins2BuildFull {
  import XmlUtils.text

  def unmarshal(number: String, url: String, xml: Node): BuildFull = {

    def changes = 
      (xml \ "changeSet" \ "item")
      .filter {_.isInstanceOf[Elem]}
      .map {i => new Change(
            text(i, "user")
            , text(i, "date")
            , text(i, "time")
            , text(i, "msg")
          )
      }

    new BuildFull(
      number
      , url
      , (xml \ "timestamp").text.trim
      , (xml \ "artifact" \ "fileName").map {_.text.trim}
      , changes
    )
  }
}

class Change(
  val author: String
  , val date: String
  , val time: String
  , val message: String
)

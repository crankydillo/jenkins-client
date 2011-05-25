/**
* Copyright 2010 Samuel Cox
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.beeherd.jenkins.client

/**
* Implementation will be called as near the end of the retrieval process.
* Primarily intended for those who find Javascript too slow.
*
* @author scox
*/

import java.io.File

trait PostProcessor {

  /**
  * This will be near the end of the retrieval process.
  *
  * @installDir The directory that has been created 
  */
  def process(artifact: File, installDir: File): Unit
}

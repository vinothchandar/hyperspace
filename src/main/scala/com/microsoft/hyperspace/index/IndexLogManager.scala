/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.index

import java.util.UUID

import scala.util.Try

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, FileUtil, Path}
import org.apache.spark.internal.Logging

import com.microsoft.hyperspace.actions.Constants
import com.microsoft.hyperspace.util.{FileUtils, JsonUtils}

/**
 * Interface for IndexLogManager which handles log operations.
 */
trait IndexLogManager {
  def getLog(id: Int): Option[LogEntry]

  def getLatestId(): Option[Int]

  // TODO: This should be marked as final - remove test dependency.
  def getLatestLog(): Option[LogEntry] = getLatestId() match {
    case Some(id) => getLog(id)
    case None => None
  }

  /** Returns the latest LogEntry whose state is STABLE */
  def getLatestStableLog(): Option[LogEntry]

  /** update latest.json symlink to the given id/path */
  def createLatestStableLog(id: Int): Boolean

  /** delete latestStable.json */
  def deleteLatestStableLog(): Boolean

  /** write contents of log to id.json using optimistic concurrency. retrun false if fail */
  def writeLog(id: Int, log: LogEntry): Boolean
}

class IndexLogManagerImpl(indexPath: Path) extends IndexLogManager with Logging {
  // Use FileContext instead of FileSystem for atomic renames?
  private lazy val fs: FileSystem = indexPath.getFileSystem(new Configuration)

  private lazy val hyperspaceLogPath: Path = new Path(indexPath, IndexConstants.HYPERSPACE_LOG)

  private val pathFromId: Int => Path = id => new Path(hyperspaceLogPath, id.toString)

  private val LATEST_STABLE_LOG_NAME = "latestStable"

  private val latestStablePath = new Path(hyperspaceLogPath, LATEST_STABLE_LOG_NAME)

  private def getLog(path: Path): Option[LogEntry] = {
    if (!fs.exists(path)) {
      return None
    }
    val contents = FileUtils.readContents(fs, path)
    Some(LogEntry.fromJson(contents))
  }

  override def getLog(id: Int): Option[LogEntry] = {
    getLog(pathFromId(id))
  }

  override def getLatestId(): Option[Int] = {
    if (!fs.exists(hyperspaceLogPath)) {
      return None
    }
    val ids = fs.listStatus(hyperspaceLogPath).collect {
      case file: FileStatus if Try(file.getPath.getName.toInt).toOption.isDefined =>
        file.getPath.getName.toInt
    }
    if (ids.isEmpty) None else Some(ids.max)
  }

  override def getLatestStableLog(): Option[LogEntry] = {
    val latestStableLogPath = new Path(hyperspaceLogPath, LATEST_STABLE_LOG_NAME)

    val log = getLog(latestStableLogPath)
    if (log.isEmpty) {
      val idOpt = getLatestId()
      if (idOpt.isDefined) {
        (idOpt.get to 0 by -1).foreach { id =>
          val entry = getLog(id)
          if (entry.isDefined && Constants.STABLE_STATES.contains(entry.get.state)) {
            return entry
          }
        }
      }
      None
    } else {
      assert(Constants.STABLE_STATES.contains(log.get.state))
      log
    }
  }

  override def createLatestStableLog(id: Int): Boolean = {
    // TODO: make sure log with the id has a stable state.
    try {
      FileUtil.copy(fs, pathFromId(id), fs, latestStablePath, false, new Configuration)
    } catch {
      case ex: Exception =>
        logError(s"Failed to create the latest stable log with id = '$id'", ex)
        false
    }
  }

  override def deleteLatestStableLog(): Boolean = {
    try {
      if (!fs.exists(latestStablePath)) {
        true
      } else {
        fs.delete(latestStablePath, true)
      }
    } catch {
      case ex: Exception =>
        logError("Failed to delete the latest stable log", ex)
        false
    }
  }

  override def writeLog(id: Int, log: LogEntry): Boolean = {
    if (fs.exists(pathFromId(id))) {
      false
    } else {
      try {
        val tempPath = new Path(hyperspaceLogPath, "temp" + UUID.randomUUID())
        FileUtils.createFile(fs, tempPath, JsonUtils.toJson(log))

        // Atomic rename: if rename fails, someone else succeeded in writing id json
        fs.rename(tempPath, pathFromId(id))
      } catch {
        case ex: Exception =>
          logError(s"Failed to write log with id = '$id'", ex)
          false
      }
    }
  }
}

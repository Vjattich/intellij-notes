// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.idea

import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.diagnostic.runActivity
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.application.impl.RawSwingDispatcher
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.impl.FrameBoundsConverter
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.ui.Splash
import com.intellij.ui.loadSplashImage
import com.intellij.util.ui.StartupUiUtil
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.WindowConstants

@Volatile
private var PROJECT_FRAME: JFrame? = null
private var SPLASH_WINDOW: Splash? = null

internal suspend fun scheduleShowSplash(initUiDeferred: Job, appInfoDeferred: Deferred<ApplicationInfoEx>) {
  // A splash instance must not be created before base LaF is created.
  // It is important on Linux, where GTK LaF must be initialized (to properly set up the scale factor).
  // https://youtrack.jetbrains.com/issue/IDEA-286544
  initUiDeferred.join()

  // before showEuaIfNeededJob to prepare during showing EUA dialog
  // products may specify `splash` VM property; `nosplash` is deprecated and should be checked first
  val appInfo = appInfoDeferred.await()
  val runnable = runActivity("splash preparation") {
    try {
      scheduleShow(appInfo)
    }
    catch (e: Exception) {
      logger<StartupUiUtil>().error("Cannot schedule splash showing", e)
      return
    }
  }
  withContext(RawSwingDispatcher) {
    runnable()
  }
}

private fun scheduleShow(appInfo: ApplicationInfoEx): () -> Unit {
  runActivity("splash as project frame initialization") {
    try {
      createFrameIfPossible()
    }
    catch (e: Throwable) {
      logger<Splash>().error(e)
      null
    }
  }?.let {
    return it
  }

  assert(SPLASH_WINDOW == null)
  val activity = StartUpMeasurer.startActivity("splash initialization")
  val image = loadSplashImage(appInfo = appInfo)
  val queueActivity = activity.startChild("splash initialization (in queue)")
  return {
    queueActivity.end()
    SPLASH_WINDOW = Splash(image)
    activity.end()
  }
}

private fun createFrameIfPossible(): (() -> Unit)? {
  val infoFile = Path.of(PathManager.getSystemPath(), "lastProjectFrameInfo")
  var buffer: ByteBuffer
  try {
    Files.newByteChannel(infoFile).use { channel ->
      buffer = ByteBuffer.allocate(channel.size().toInt())
      do {
        channel.read(buffer)
      }
      while (buffer.hasRemaining())
      buffer.flip()
      if (buffer.getShort().toInt() != 0) {
        return null
      }
    }
  }
  catch (ignore: NoSuchFileException) {
    return null
  }

  val savedBounds = Rectangle(buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt())

  @Suppress("UseJBColor")
  val backgroundColor = Color(buffer.getInt(), true)

  @Suppress("UNUSED_VARIABLE")
  val isFullScreen = buffer.get().toInt() == 1
  val extendedState = buffer.getInt()
  return {
    try {
      PROJECT_FRAME = doShowFrame(savedBounds = savedBounds, backgroundColor = backgroundColor, extendedState = extendedState)
    }
    catch (e: Throwable) {
      logger<Splash>().error(e)
    }
  }
}

internal fun getAndUnsetSplashProjectFrame(): JFrame? {
  val frame = PROJECT_FRAME
  PROJECT_FRAME = null
  return frame
}

fun hideSplashBeforeShow(window: Window) {
  if (SPLASH_WINDOW != null || PROJECT_FRAME != null) {
    window.addWindowListener(object : WindowAdapter() {
      override fun windowOpened(e: WindowEvent) {
        hideSplash()
        window.removeWindowListener(this)
      }
    })
  }
}

fun hideSplash() {
  var window: Window? = SPLASH_WINDOW
  if (window == null) {
    window = PROJECT_FRAME ?: return
    PROJECT_FRAME = null
  }
  else {
    SPLASH_WINDOW = null
  }

  StartUpMeasurer.addInstantEvent("splash hidden")
  window.isVisible = false
  window.dispose()
}

private fun doShowFrame(savedBounds: Rectangle, backgroundColor: Color, extendedState: Int): IdeFrameImpl {
  val frame = IdeFrameImpl()
  frame.isAutoRequestFocus = false
  frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
  val devicePair = FrameBoundsConverter.convertFromDeviceSpaceAndFitToScreen(savedBounds)
  // this functionality under the flag - fully correct behavior is not needed here (that's default is not applied if null)
  if (devicePair != null) {
    frame.bounds = devicePair.first
  }
  frame.extendedState = extendedState
  frame.minimumSize = Dimension(340, frame.minimumSize.getHeight().toInt())
  frame.background = backgroundColor
  frame.contentPane.background = backgroundColor
  if (SystemInfoRt.isMac) {
    frame.iconImage = null
  }
  StartUpMeasurer.addInstantEvent("frame shown")
  val activity = StartUpMeasurer.startActivity("frame set visible")
  frame.isVisible = true
  activity.end()
  return frame
}
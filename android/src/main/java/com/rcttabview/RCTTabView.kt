package com.rcttabview

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSources
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.image.CloseableBitmap
import com.facebook.imagepipeline.request.ImageRequestBuilder
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.ReactChoreographer
import com.facebook.react.views.imagehelper.ImageSource
import com.google.android.material.bottomnavigation.BottomNavigationView


class ReactBottomNavigationView(context: Context) : BottomNavigationView(context) {
  private val icons: MutableMap<Int, ImageSource> = mutableMapOf()
  private var isLayoutEnqueued = false
  var items: MutableList<TabInfo>? = null
  var onTabSelectedListener: ((WritableMap) -> Unit)? = null
  var onTabLongPressedListener: ((WritableMap) -> Unit)? = null
  private var isAnimating = false
  private var activeTintColor: Int? = null
  private var inactiveTintColor: Int? = null
  private val checkedStateSet = intArrayOf(android.R.attr.state_checked)
  private val uncheckedStateSet = intArrayOf(-android.R.attr.state_checked)
  private var hapticFeedbackEnabled = true

  private val layoutCallback = Choreographer.FrameCallback {
    isLayoutEnqueued = false
    measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    layout(left, top, right, bottom)
  }

  init {
    setOnItemSelectedListener { item ->
      onTabSelected(item)
      updateTintColors(item)
      true
    }
  }

  private fun onTabLongPressed(item: MenuItem) {
    val longPressedItem = items?.firstOrNull { it.title == item.title }
    longPressedItem?.let {
      val event = Arguments.createMap().apply {
        putString("key", longPressedItem.key)
      }
      onTabLongPressedListener?.invoke(event)
      emitHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
  }

  override fun requestLayout() {
    super.requestLayout()
    @Suppress("SENSELESS_COMPARISON") // layoutCallback can be null here since this method can be called in init
    if (!isLayoutEnqueued && layoutCallback != null) {
      isLayoutEnqueued = true
      // we use NATIVE_ANIMATED_MODULE choreographer queue because it allows us to catch the current
      // looper loop instead of enqueueing the update in the next loop causing a one frame delay.
      ReactChoreographer
        .getInstance()
        .postFrameCallback(
          ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE,
          layoutCallback,
        )
    }
  }

  private fun onTabSelected(item: MenuItem) {
    if (isLayoutEnqueued) {
      return;
    }
    val selectedItem = items?.first { it.title == item.title }
    selectedItem?.let {
      val event = Arguments.createMap().apply {
        putString("key", selectedItem.key)
      }
      onTabSelectedListener?.invoke(event)
      emitHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
  }

  fun updateItems(items: MutableList<TabInfo>) {
    this.items = items
    items.forEachIndexed { index, item ->
      val menuItem = getOrCreateItem(index, item.title)
      menuItem.isVisible = !item.hidden
      if (icons.containsKey(index)) {
        menuItem.icon = getDrawable(icons[index]!!)
      }
      if (item.badge.isNotEmpty()) {
        val badge = this.getOrCreateBadge(index)
        badge.isVisible = true
        badge.text = item.badge
      } else {
        removeBadge(index)
      }
      post {
        findViewById<View>(menuItem.itemId).setOnLongClickListener {
          onTabLongPressed(menuItem)
          true
        }
      }
    }
  }

  private fun getOrCreateItem(index: Int, title: String): MenuItem {
    return menu.findItem(index) ?: menu.add(0, index, 0, title)
  }

  fun setIcons(icons: ReadableArray?) {
    if (icons == null || icons.size() == 0) {
      return
    }

    for (idx in 0 until icons.size()) {
      val source = icons.getMap(idx)
      val uri = source.getString("uri")
      if (uri.isNullOrEmpty()) {
        continue
      }
      val imageSource =
      ImageSource(
        context,
        uri
      )
      this.icons[idx] = imageSource

      // Update existing item if exists.
      menu.findItem(idx)?.let { menuItem ->
        menuItem.icon = getDrawable(imageSource)
      }
    }
  }

  fun setLabeled(labeled: Boolean?) {
    labelVisibilityMode = if (labeled == false) {
      LABEL_VISIBILITY_UNLABELED
    } else if (labeled == true) {
      LABEL_VISIBILITY_LABELED
    } else {
      LABEL_VISIBILITY_AUTO
    }
  }

  fun setRippleColor(color: ColorStateList) {
    itemRippleColor = color
  }

  private fun getDrawable(imageSource: ImageSource): Drawable {
    // TODO: Check if this can be done using some built-in React Native class
    val imageRequest = ImageRequestBuilder.newBuilderWithSource(imageSource.uri).build()
    val dataSource = Fresco.getImagePipeline().fetchDecodedImage(imageRequest, context)
    val result = DataSources.waitForFinalResult(dataSource) as CloseableReference<CloseableBitmap>
    val bitmap = result.get().underlyingBitmap

    CloseableReference.closeSafely(result)
    dataSource.close()

    return BitmapDrawable(resources, bitmap)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    isAnimating = false
  }

  fun setBarTintColor(color: Int?) {
    // Set the color, either using the active background color or a default color.
    val backgroundColor = color ?: getDefaultColorFor(android.R.attr.colorPrimary) ?: return

    // Apply the same color to both active and inactive states
    val colorDrawable = ColorDrawable(backgroundColor)

    itemBackground = colorDrawable
    backgroundTintList = ColorStateList.valueOf(backgroundColor)
  }

  fun setActiveTintColor(color: Int?) {
    activeTintColor = color
    updateTintColors()
  }

  fun setInactiveTintColor(color: Int?) {
    inactiveTintColor = color
    updateTintColors()
  }

  fun setActiveIndicatorColor(color: ColorStateList) {
    itemActiveIndicatorColor = color
  }

  fun setHapticFeedback(enabled: Boolean) {
    hapticFeedbackEnabled = enabled
  }

  fun emitHapticFeedback(feedbackConstants: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hapticFeedbackEnabled) {
      this.performHapticFeedback(feedbackConstants)
    }
  }

  private fun updateTintColors(item: MenuItem? = null) {
    // First let's check current item color.
    val currentItemTintColor = items?.find { it.title == item?.title }?.activeTintColor

    // getDeaultColor will always return a valid color but to satisfy the compiler we need to check for null
    val colorPrimary = currentItemTintColor ?: activeTintColor ?: getDefaultColorFor(android.R.attr.colorPrimary) ?: return
    val colorSecondary =
      inactiveTintColor ?: getDefaultColorFor(android.R.attr.textColorSecondary) ?: return
    val states = arrayOf(uncheckedStateSet, checkedStateSet)
    val colors = intArrayOf(colorSecondary, colorPrimary)

    ColorStateList(states, colors).apply {
      this@ReactBottomNavigationView.itemTextColor = this
      this@ReactBottomNavigationView.itemIconTintList = this
    }
  }

  private fun getDefaultColorFor(baseColorThemeAttr: Int): Int? {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(baseColorThemeAttr, value, true)) {
      return null
    }
    val baseColor = AppCompatResources.getColorStateList(
      context, value.resourceId
    )
    return baseColor.defaultColor
  }
}

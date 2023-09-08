Certainly! Here's the integration documentation for the `BannerManager` interface in Markdown format:

# BannerManager Integration Documentation

The `BannerManager` interface offers methods for integrating banner advertisements into your Android application. This documentation will walk you through the integration process and explain the available functions.

## Table of Contents

1. [Introduction](#introduction)
2. [Methods](#methods)
    1. [Position](#setposition)
    2. [BannerFormat](#setbannerformat)
    3. [BannerListener](#setbannerlistener)
    4. [Loading Ad](#loadad)
    5. [Displaying Ad](#showad)
    6. [Hiding Ad](#hidead)
    7. [Destroying Ad](#destroyad)

## Introduction <a name="introduction"></a>

The `BannerManager` interface is designed for integrating banner advertisements into your Android application. 
Create an instance of the `BannerManager` interface and use its methods to load and display banner ads.
```kotlin
val banner = BannerManager()
```
 
## `Banner position`  <a name="setposition"></a>

### Predefined Banner Positions

```kotlin
fun setPosition(position: BannerPosition)
```
The `BannerPosition` enum represents different positions where banner advertisements can be displayed within an Android application. It offers four possible banner positions, each serving a unique purpose:

- `HorizontalTop`: This position places the banner at the top of the screen, typically spanning horizontally.

- `HorizontalBottom`: This position places the banner at the bottom of the screen, typically spanning horizontally.

- `VerticalLeft`: This position places the banner on the left side of the screen, typically spanning vertically.

- `VerticalRight`: This position places the banner on the right side of the screen, typically spanning vertically.

#### Default Banner Position

### Custom Position

Set a custom position for the banner. You can specify the top-left offset in pixels, rotation in degrees, and the anchor point in relative coordinates (0 to 1, starting from the top-left corner).

```kotlin
fun setCustomPosition(offset: Point, rotation: Int, anchor: PointF)
```

### Hiding Ad <a name="hidead"></a>

Hide the banner ad.

```kotlin
fun hideAd()
```


### BannerFormat <a name="setbannerformat"></a>

Set the desired banner format.

```kotlin
fun setBannerFormat(bannerFormat: BannerFormat)
```


### Loading Ad <a name="loadad"></a>

Load the banner ad. You should provide the current `Activity` where the ad will be displayed. Optionally, you can specify a price floor for the ad.

```kotlin
fun loadAd(activity: Activity, pricefloor: Double = BidonSdk.DefaultPricefloor)
```

### Displaying Ad <a name="showad"></a>

Show the banner ad on the specified activity.

```kotlin
fun showAd(activity: Activity)
```

#### `isReady`

Check if the banner ad is ready to be shown.

```kotlin
fun isReady(): Boolean
```

### Destroying Ad <a name="destroyad"></a>

Destroy the banner ad when it's no longer needed.

```kotlin
fun destroyAd()
```


### BannerListener <a name="setbannerlistener"></a>
Set a listener to receive callbacks for banner ad events.

```kotlin
fun setBannerListener(listener: BannerListener?)
```

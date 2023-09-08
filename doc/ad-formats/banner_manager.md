Certainly! Here's the integration documentation for the `BannerManager` interface in Markdown format:

# BannerManager Integration Documentation

The `BannerManager` interface offers methods for integrating banner advertisements into your Android application. This documentation will walk you through the integration process and explain the available functions.

## Table of Contents

1. [Introduction](#introduction)
2. [Methods](#methods)
    1. [setPosition](#setposition)
    2. [setCustomPosition](#setcustomposition)
    3. [hideAd](#hidead)
    4. [setBannerFormat](#setbannerformat)
    5. [loadAd](#loadad)
    6. [isReady](#isready)
    7. [showAd](#showad)
    8. [destroyAd](#destroyad)
    9. [setBannerListener](#setbannerlistener)

## Introduction <a name="introduction"></a>

The `BannerManager` interface is designed for integrating banner advertisements into your Android application. It provides various methods to control the positioning, visibility, and interaction with banner ads.

## Methods <a name="methods"></a>

### `setPosition` <a name="setposition"></a>

```kotlin
fun setPosition(position: BannerPosition)
```

## BannerPosition Enum

The `BannerPosition` enum represents different positions where banner advertisements can be displayed within an Android application. It offers four possible banner positions, each serving a unique purpose:

- `HorizontalTop`: This position places the banner at the top of the screen, typically spanning horizontally.

- `HorizontalBottom`: This position places the banner at the bottom of the screen, typically spanning horizontally.

- `VerticalLeft`: This position places the banner on the left side of the screen, typically spanning vertically.

- `VerticalRight`: This position places the banner on the right side of the screen, typically spanning vertically.

### Default Banner Position

The `BannerPosition` enum includes a companion object that defines a default banner position, which is `HorizontalBottom`. If no specific banner position is set, the default position will be used.

To use the `BannerPosition` enum in your code, you can assign a specific position or use the default position as follows:

```kotlin
// Setting a custom banner position
val customPosition = BannerPosition.VerticalLeft

// Getting the default banner position
val defaultPosition = BannerPosition.Default // This will be HorizontalBottom
```

Integrate these banner positions within your Android application to control where banner advertisements are displayed, offering flexibility and customization options to suit your design and user experience requirements.
```


Set the predefined `BannerPosition` for the banner. This method always considers safe area insets.

### `setCustomPosition` <a name="setcustomposition"></a>

```kotlin
fun setCustomPosition(offset: Point, rotation: Int, anchor: PointF)
```

Set a custom position for the banner. You can specify the top-left offset in pixels, rotation in degrees, and the anchor point in relative coordinates (0 to 1, starting from the top-left corner).

### `hideAd` <a name="hidead"></a>

```kotlin
fun hideAd()
```

Hide the banner ad.

### `setBannerFormat` <a name="setbannerformat"></a>

```kotlin
fun setBannerFormat(bannerFormat: BannerFormat)
```

Set the desired banner format.

### `loadAd` <a name="loadad"></a>

```kotlin
fun loadAd(activity: Activity, pricefloor: Double = BidonSdk.DefaultPricefloor)
```

Load the banner ad. You should provide the current `Activity` where the ad will be displayed. Optionally, you can specify a price floor for the ad.

### `isReady` <a name="isready"></a>

```kotlin
fun isReady(): Boolean
```

Check if the banner ad is ready to be shown.

### `showAd` <a name="showad"></a>

```kotlin
fun showAd(activity: Activity)
```

Show the banner ad on the specified activity.

### `destroyAd` <a name="destroyad"></a>

```kotlin
fun destroyAd()
```

Destroy the banner ad when it's no longer needed.

### `setBannerListener` <a name="setbannerlistener"></a>

```kotlin
fun setBannerListener(listener: BannerListener?)
```

Set a listener to receive callbacks for banner ad events.

---

This documentation provides an overview of the `BannerManager` interface and its methods. To integrate banner ads into your Android application, follow the appropriate usage for each method, ensuring that you have the necessary dependencies and configurations in place.

Please refer to the official SDK documentation or contact your ad provider for additional details and best practices for integrating and displaying banner advertisements in your Android application.

For any questions or issues related to this integration, feel free to contact our support team.

**Note:** Replace `BannerPosition`, `BannerFormat`, `BidonSdk`, and other placeholders with the actual class or variable names from your specific integration.
```

Feel free to use this Markdown template as a starting point for your integration documentation and customize it further to match your specific needs and branding.
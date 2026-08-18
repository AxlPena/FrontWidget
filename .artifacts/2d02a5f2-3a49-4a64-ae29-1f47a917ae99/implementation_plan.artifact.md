# Implementation Plan - Weather Click Action & Location Polish

This plan adds a one-click action to the weather section to open a detailed weather report and improves location geocoding for regions like Japan.

## Proposed Changes

### [Component] App Widget
Update the weather section to be fully interactive and open a detailed weather link.

#### [MODIFY] [FrontWidget.kt](file:///C:/Users/Adria/project/FrontWidget/app/src/main/java/com/saveory/frontwidget/FrontWidget.kt)
- **Interactive Weather Row**: Make the entire `WeatherDisplay` row clickable using `GlanceModifier.clickable`.
- **Weather URL**: Use a more detailed weather URL: `https://www.google.com/search?q=weather+${Uri.encode(weatherLocality)}`. (This mimics the "detail view" expected from KWGT widgets).
- **UI Polish**: Ensure the weather icon and text are perfectly aligned within the clickable area.

### [Component] Weather & Location
Improve geocoding robustness to ensure "State" (Prefecture/Region) is not missing for international locations.

#### [MODIFY] [WeatherWorker.kt](file:///C:/Users/Adria/project/FrontWidget/app/src/main/java/com/saveory/frontwidget/WeatherWorker.kt)
- **Enhanced Geocoding**: Update the logic to capture `adminArea` (States/Prefectures) more reliably.
- **International Support**: If `locality` is missing but `subLocality` exists (common in Japan), use `subLocality` as the primary city name.

## Verification Plan

### Manual Verification
1.  **Weather Click**: Tap any part of the weather row (condition, icon, or temp).
    - Verify: The browser opens with a detailed weather report for your current city.
2.  **Japan Geocoding**: Verify the second line shows "Tokyo, JP" (or appropriate prefecture) instead of just "JP".
3.  **Emulator Sync**: Force a location refresh and verify the layout remains balanced.

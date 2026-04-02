Place Samsung's `samsung-health-sensor-api.aar` in this `app/libs` folder.

`app/build.gradle.kts` now references this file explicitly:
`implementation(files("libs/samsung-health-sensor-api.aar"))`.

# Fix "Cannot add extension with name 'kotlin'" Sync Error

The error occurs because of inconsistent plugin declarations and version mismatches (specifically KSP version 2.0.0 conflicting with Kotlin version 2.2.10). This leads to multiple versions of the Kotlin Gradle Plugin being loaded, which causes a conflict when registering the `kotlin` extension.

## Proposed Changes

### [Component] Gradle Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/user/AndroidStudioProjects/dubmusic/gradle/libs.versions.toml)
- Add `kotlin-android` plugin definition.
- Add `ksp` plugin definition.
- Update `ksp` version to match the Kotlin version (`2.2.10`).

#### [MODIFY] [build.gradle.kts (root)](file:///C:/Users/user/AndroidStudioProjects/dubmusic/build.gradle.kts)
- Declare `kotlin-android` and `ksp` plugins in the root `plugins` block with `apply false` to ensure version consistency across all modules.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/user/AndroidStudioProjects/dubmusic/app/build.gradle.kts)
- Replace manual plugin declarations with aliases from `libs.versions.toml`.
- Remove manual versions from the `plugins` block.

## Verification Plan

### Automated Tests
- Run Gradle Sync to verify the issue is resolved.
- Run `./gradlew assembleDebug` to ensure the project builds correctly.

# XRay.xcframework GitHub Release Guide

This guide explains how to upload the XRay.xcframework to GitHub Releases for automated distribution via CocoaPods.

## Framework Details

- **File**: `ios/XRay.xcframework.zip` (31 MB)
- **SHA256**: `ab14d797a3efe5cd148054e7bd1923d95b355cee9b0a36e5a81782c6fd517d1a`

## Steps to Upload to GitHub Releases

### 1. Create a New Release

1. Go to your repository: https://github.com/shafiquecbl/flutter_v2ray_plus
2. Click on "Releases" in the right sidebar
3. Click "Draft a new release" or "Create a new release"

### 2. Configure the Release

- **Tag version**: `framework-v1.0.0` (or match your package version)
- **Release title**: `XRay Framework v1.0.0`
- **Description**: 
  ```
  XRay.xcframework for iOS support in flutter_v2ray_plus
  
  This framework is automatically downloaded during pod install.
  Users do not need to download this manually.
  
  **SHA256**: ab14d797a3efe5cd148054e7bd1923d95b355cee9b0a36e5a81782c6fd517d1a
  ```

### 3. Upload the Framework

1. In the "Attach binaries" section at the bottom, click to select files
2. Upload `ios/XRay.xcframework.zip`
3. Click "Publish release"

### 4. Get the Download URL

After publishing, the download URL will be:
```
https://github.com/shafiquecbl/flutter_v2ray_plus/releases/download/framework-v1.0.0/XRay.xcframework.zip
```

This URL is already configured in `ios/flutter_v2ray_pro.podspec`.

## Updating for Future Versions

When updating the framework:

1. Compress the updated framework: `cd ios && zip -r XRay.xcframework.zip XRay.xcframework`
2. Generate new SHA256 hash: `shasum -a 256 ios/XRay.xcframework.zip`
3. Create a new GitHub release with the new tag version
4. Update the podspec with:
   - New release tag in the `:http` URL
   - New SHA256 hash in `:sha256`
5. Update package version in `pubspec.yaml` and republish to pub.dev

## Verification

After uploading, test the installation:
```bash
# In a test Flutter project
flutter pub add flutter_v2ray_plus
cd ios
pod install --verbose
```

The framework should download automatically during `pod install`.

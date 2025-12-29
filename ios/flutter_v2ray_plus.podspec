#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_v2ray_plus.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_v2ray_plus'
  s.version          = '1.0.13'
  s.summary          = 'Flutter plugin to run VLESS/VMESS as a local proxy and VPN on Android and iOS.'
  s.description      = <<-DESC
Flutter plugin to run VLESS/VMESS as a local proxy and VPN on Android and iOS.
V2Ray/Xray core with Shadowsocks, Trojan, and Socks 5 support.
The XRay.xcframework is automatically downloaded from GitHub Releases during pod install.
                       DESC
  s.homepage         = 'https://github.com/shafiquecbl/flutter_v2ray_plus'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Shafique' => 'wisecodexinfo@gmail.com' }
  s.source           = { :path => '.' }
  
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '11.0'

  # Download XRay.xcframework from GitHub Releases during pod install
  s.prepare_command = <<-CMD
    set -e
    FRAMEWORK_URL="https://github.com/shafiquecbl/flutter_v2ray_plus/releases/download/framework-v1.0.0/XRay.xcframework.zip"
    FRAMEWORK_SHA256="ab14d797a3efe5cd148054e7bd1923d95b355cee9b0a36e5a81782c6fd517d1a"
    
    if [ ! -d "XRay.xcframework" ]; then
      echo "Downloading XRay.xcframework from GitHub Releases..."
      curl -L -o XRay.xcframework.zip "$FRAMEWORK_URL"
      
      echo "Verifying SHA256 checksum..."
      echo "$FRAMEWORK_SHA256  XRay.xcframework.zip" | shasum -a 256 -c -
      
      echo "Extracting XRay.xcframework..."
      unzip -q XRay.xcframework.zip
      rm XRay.xcframework.zip
      echo "XRay.xcframework downloaded and extracted successfully!"
    else
      echo "XRay.xcframework already exists, skipping download."
    fi
  CMD

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.xcconfig = { 'OTHER_LDFLAGS' => '-framework XRay' }
  s.libraries = 'resolv'
  s.vendored_frameworks = 'XRay.xcframework'
  s.swift_version = '5.0'
end

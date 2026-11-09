# AS300 リモート 0.2

ソニー HDR-AS300のライブビュー表示と録画開始・停止をWi-Fi経由で行うAndroidアプリです。

- 対象OSはAndroid 7.0とAndroid 8.0です。
- 実機確認機種はHUAWEI nova lite（PRA-LX2）です。

***このアプリは、作者が仕様を決め、Codexを使って実装・修正しました。

## APKのダウンロード

**[署名済みAPK v0.2をダウンロード](https://github.com/minillatk/as300-remote/releases/download/v0.2/AS300-Remote-0.2-release.apk)**

配布ファイルはリリースページの「Assets」にあります。[リリース v0.2](https://github.com/minillatk/as300-remote/releases/tag/v0.2)

## 実機で試す

1. APKをPRA-LX2にコピーし、スマホのファイルアプリで開いてインストールします。OSが求めた場合は、APKを開いたアプリにインストールを許可します。
2. AS300にmicroSDカードを入れ、電源を入れます。本体設定のWi-Fiをシングル接続にして、動画モードにします。
3. PRA-LX2のWi-Fiと位置情報をオンにして、「AS300 リモート」の「QRコードで接続」を押します。カメラと位置情報の権限を許可し、AS300のバッテリーカバー裏のQRコードを読み取ります。位置情報の権限はAndroidでWi-Fiの接続先を確認するために使います。GPS座標は取得しません。
4. スマホに「インターネットに接続されていません」と表示された場合は、AS300のWi-Fiへの接続を維持します。自動接続できなければ、スマホのWi-Fi設定でラベル記載のSSIDを選び、パスワードを入力してから「接続済みのAS300」を押します。
5. ライブビューが出たら「録画開始」を押します。AS300本体で選択した画質で録画されます。「録画停止」を押すと、AS300がmicroSDカードにファイルを保存します。

スマホを横向きにすると、左にライブビュー、右に操作ボタンが縦に並びます。表示する解像度はAS300が送るライブビューの実寸です。映像と録画指示はWi-Fiを使います。

切断後も録画が続いている場合はAS300本体で停止してください。接続で困った場合は、Androidバージョンとアプリのメッセージを控えてください。QRコードやWi-Fiパスワードの共有は不要です。

## Windowsでビルド

JDK 17、Android SDK Platform 34、Android SDK Build Tools 34.0.0、ZXing Core 3.5.3のJARを用意します。[ZXingの配布ページ](https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/)

初回公開時に、リポジトリの外に作成したフォルダーに配布用キーストアを作ります。例の `C:\Signing` は実際に用意した保存先に置き換えてください。パスワードは実行時に入力します。

```powershell
& 'C:\jdk-17\bin\keytool.exe' -genkeypair -storetype PKCS12 `
  -keystore 'C:\Signing\as300-release.p12' -alias as300-release `
  -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=AS300 Remote'
```

次に、ソースのフォルダーでビルドします。スクリプトはキーストアのパスワードを実行時に求め、APKを署名して検証します。

```powershell
.\build.ps1 -Jdk 'C:\jdk-17' -PlatformJar 'C:\Android\platforms\android-34\android.jar' `
  -BuildTools 'C:\Android\build-tools\34.0.0' -Zxing 'C:\deps\core-3.5.3.jar' `
  -KeyStore 'C:\Signing\as300-release.p12' -KeyAlias 'as300-release' `
  -Apk '.\AS300-Remote-0.2.apk'
```

署名済みAPKはGitHub Releasesに添付します。キーストアとパスワードは手元で保管し、安全な場所にバックアップします。更新版は同じパッケージ名と署名鍵を使い、`AndroidManifest.xml` の `versionCode` を増やします。試作版など別の鍵で署名したAPKが入っている場合、配布版へ切り替える際に一度アンインストールが必要です。[Androidのアプリ署名ガイド](https://developer.android.com/studio/publish/app-signing)

## 参考資料

- [ソニーのHDR-AS300接続ガイド](https://support.d-imaging.sony.co.jp/www/cscs/pmm/page2.php?abil=31100&area=jp&lang=jp&mdl=HDR-AS300)
- [ソニーによるCamera Remote API betaの公開発表](https://www.sony-asia.com/corporate/resources/en_AP/pdf/AP%2805September13%29-SonyToReleaseAPIForUsingMobileDevicesAsWi-FiRemoteControlsForSonyCameras.pdf)
- [ImagingEdge-NextのSony W01形式の実装](https://github.com/kalicyh/ImagingEdge-Next/blob/main/lib/services/qr_scanner_service.dart)

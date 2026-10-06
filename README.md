# Lightning Browser [![Build](https://github.com/anthonycr/Lightning-Browser/actions/workflows/action.yml/badge.svg)](https://github.com/anthonycr/Lightning-Browser/actions/workflows/action.yml)

### Speed, Simplicity, Security
![](launcher_icon_small.png)

### Download
[<img src="https://f-droid.org/badge/get-it-on.png"
      alt="Get it on F-Droid"
      height="80">](https://f-droid.org/app/acr.browser.lightning) [<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" 
alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=acr.browser.lightning)

### Features
* Bookmarks

* History

* Multiple search engines (Google, Bing, Yahoo, StartPage, DuckDuckGo, etc.)

* Incognito mode

* Unique utilization of navigation drawer or bottom drawer for tabs

* Customizable search suggestions

### 固定网站黑名单

本分支仅按项目根目录的 `Distractions websites.txt` 封锁网站。该文件是唯一名单来源；
修改后需重新构建并安装 APK。软件内无法编辑、导入、更新、关闭或临时放行名单，
旧版白名单、广告过滤设置及开放时段均不再生效。

名单使用 UTF-8 编码，一行一个域名，可包含空行和 `#` 注释。例如：

```text
# 封锁该域名及其全部子域名
example.com
# 仅封锁这个子域名及其后代，主域名仍允许访问
news.example.org
```

请填写域名，不要写协议、端口、路径或通配符。支持国际化域名，匹配时忽略大小写和
末尾点号；`example.com` 不会匹配 `otherexample.com`。空名单允许全部网站。
HTTP(S) 页面、内嵌页面和资源请求均使用同一名单；黑名单域名的下载链接也不会因
文件扩展名而放行。浏览器内部页面仍可访问。未列出的域名正常访问，不使用旧广告 hosts。

推送到 `main`、向 `main` 提交 PR，或手动运行 GitHub Actions 的 **Build** workflow：

1. 校验名单格式，错误会报告文件名及行号。
2. 执行 Gradle 构建、单元测试和项目检查，将名单原样打包为 `assets/blacklist.txt`。
3. 校验每个 APK 内的名单与此次提交一致。
4. 在运行结果的 Artifacts 中下载 `lightning-browser-debug-<commit SHA>`，解压后安装 APK。

上传的 APK 使用 debug 签名；没有配置发布签名。修改名单后再次运行同一流程即可。

### Permissions

#### Automatically granted
* `INTERNET`: necessary to access the internet.
* `ACCESS_NETWORK_STATE`: used by the browser to stop loading resources when network access is lost.
* `INSTALL_SHORTCUT`: used to add shortcuts with the "Add to home screen" option.
* `POST_NOTIFICATIONS`: used to display notifications.

#### Requested only when needed
* `WRITE_EXTERNAL_STORAGE`: needed to download files and export bookmarks.
* `READ_EXTERNAL_STORAGE`: needed to download files and import bookmarks.
* `ACCESS_FINE_LOCATION`: needed for sites like Google Maps, requires "Location access" option to be enabled (default disabled).
* `RECORD_AUDIO`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).
* `CAMERA`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).
* `MODIFY_AUDIO_SETTINGS`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).

### Contributing
* Contributions are always welcome
* Make pull requests into the `main` branch.

### License
```
Copyright 2014 Anthony Restaino

Lightning Browser

   This Source Code Form is subject to the terms of the 
   Mozilla Public License, v. 2.0. If a copy of the MPL 
   was not distributed with this file, You can obtain one at 
   
   http://mozilla.org/MPL/2.0/
```

# App 签名与备份 — 钱包哨兵

**签名密钥是 Google Play 上架后唯一不可更换的凭证。丢失密钥 = 永远无法更新应用。**

---

## 一、签名证书信息

| 项目 | 值 |
|---|---|
| **Keystore 文件** | `deepseek-balance.jks` |
| **别名 (Alias)** | `deepseek` |
| **算法** | RSA 2048-bit |
| **签名算法** | SHA256withRSA |
| **有效期** | 2026-07-05 ~ 2053-11-20（10,000 天 / ~27 年） |
| **SHA1 指纹** | `AF:C6:F0:BE:79:99:7F:EA:A9:B3:39:43:12:F3:0E:FA:D1:74:B9:73` |
| **SHA256 指纹** | `31:9A:A8:DA:E3:39:E8:C9:5E:55:38:33:16:05:55:0D:7A:BC:94:99:2C:BE:B5:CE:54:B7:4B:27:6C:CB:AD:3F` |
| **Subject** | CN=Balance Sentinel, OU=Dev, O=Balance Sentinel, L=Beijing, ST=Beijing, C=CN |

---

## 二、生成命令（重建用）

仅在原始 keystore 完全丢失且所有备份都无法恢复时使用。此命令会生成**不同的密钥**，届时需要联系 Google Play 支持申请密钥升级。

```bash
keytool -genkey -v \
  -keystore deepseek-balance.jks \
  -alias deepseek \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "<your-keystore-password>" \
  -keypass "<your-keystore-password>" \
  -dname "CN=Balance Sentinel, OU=Dev, O=Balance Sentinel, L=Beijing, ST=Beijing, C=CN"
```

---

## 三、备份位置

| 位置 | 路径 | 用途 |
|---|---|---|
| **主副本** | `DeepSeekBalance/deepseek-balance.jks` | 日常构建 |
| **备份 1** | `~/.balance-sentinel/deepseek-balance.jks.backup` | 本地磁盘备份 |
| **备份 2（建议）** | Google Drive / 1Password / 加密 U 盘 | 异地备份 |
| **备份 3（建议）** | 打印纸质 SHA256 指纹存档 | 身份验证 |

### 关键密码

| 密码 | 值 |
|---|---|
| **Keystore 密码** | `<your-keystore-password>` |
| **Key 密码** | `<your-keystore-password>` |

> ⚠️ 以上密码和 `.jks` 文件应存储在密码管理器（1Password / Bitwarden / KeePass）中。
> 不要将密码以明文形式存放在公开仓库。

---

## 四、Google Play 签名方案选择

### 方案 A：Google 管理签名密钥（推荐，免费）

Play Console → 应用完整性 → 应用签名：
1. 首次上传时选择「让 Google 管理应用签名密钥」
2. 使用本 keystore 签名首次上传的 AAB
3. Google 保存你的上传密钥，生成独立的「应用签名密钥」用于分发给用户
4. **好处**：即使丢失上传密钥，Google 可以帮你重置（需验证身份）

### 方案 B：自行管理签名密钥

1. 每次上传使用本 keystore 签名
2. **风险**：密钥丢失 = 应用永久无法更新
3. 不推荐，除非有严格的合规要求

> **强烈建议选择方案 A**。2026 年起 Google Play 已默认推荐 Play App Signing。

---

## 五、Play Console 注册用信息

在 Play Console 创建应用后，需提供以下信息验证所有权：

- SHA1: `AF:C6:F0:BE:79:99:7F:EA:A9:B3:39:43:12:F3:0E:FA:D1:74:B9:73`
- SHA256: `31:9A:A8:DA:E3:39:E8:C9:5E:55:38:33:16:05:55:0D:7A:BC:94:99:2C:BE:B5:CE:54:B7:4B:27:6C:CB:AD:3F`

---

## 六、验证签名

签名后验证 APK/AAB 是否使用了正确的密钥：

```bash
# 验证 APK 签名
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-release.apk

# 验证 AAB 签名
jarsigner -verify -verbose -certs app-release.aab
```

---

## 七、build.gradle.kts 配置参考

```kotlin
// Release 签名配置 — 从 keystore.properties 读取（该文件不提交到 git）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

signingConfigs {
    if (hasKeystoreConfig) {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
}
```

---

## 八、紧急恢复流程

如果主 keystore 丢失：

1. **方案 A 用户**：在 Play Console → 应用完整性 → 请求密钥升级，Google 会要求提供所有权证明
2. **方案 B 用户**：从备份恢复 `.jks` 到项目根目录，恢复 `keystore.properties` 中的密码
3. 如果所有备份均丢失：无法更新应用。必须创建新应用（新 package name），通知用户迁移

---

## 九、环境变量方案（CI/CD 备选）

CI/CD 环境不使用 `keystore.properties` 文件，改为环境变量：

```bash
export ANDROID_KEYSTORE_B64=$(base64 -w0 deepseek-balance.jks)
export ANDROID_KEYSTORE_PASSWORD="<your-keystore-password>"
export ANDROID_KEY_ALIAS="deepseek"
export ANDROID_KEY_PASSWORD="<your-keystore-password>"
```

CI 脚本在构建前解码 keystore：
```bash
echo "$ANDROID_KEYSTORE_B64" | base64 -d > deepseek-balance.jks
```

---

## 十、开发包与正式包互相覆盖

Android 只认私钥：两个 APK 的包名相同、签名证书也必须相同，才能原地升级（保留数据）。
因此想让本地 `assembleDebug` 的产物直接覆盖手机上已装的正式版，本地必须持有**同一把正式私钥**。

### 本地有正式密钥时

把 `deepseek-balance.jks` 与 `keystore.properties` 放到仓库根目录（两者都在 `.gitignore` 中），然后：

```bash
./gradlew.bat assembleDebug --no-daemon      # 正式签名 + 可调试
./gradlew.bat assembleRelease --no-daemon    # 正式签名 + R8 混淆
```

`app/build.gradle.kts` 的 `buildTypes` 里对 `debug` 做了如下处理，存在正式签名配置时自动切换：

```kotlin
getByName("debug") {
    signingConfigs.findByName("release")?.let { signingConfig = it }
}
```

两个变体的产物自此可以互相覆盖，也能覆盖任何用该密钥签发的历史版本；没有配置的机器不受影响，仍用 Android 默认 debug 证书。

### 本地没有正式密钥时

手动触发仓库的 `Debug APK (release-signed)` 工作流（`.github/workflows/debug-signed.yml`）：
它在 CI 里用 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD` 三个 secret 还原 keystore，
构建 `assembleDebug -PwalletSentinel.signingConfigFile=keystore.properties`，
并核对签名证书 SHA-256 是否等于 `319AA8DA…CCBAD3F`，最后把 APK 作为 artifact 上传（保留 14 天）。

### 为什么不能只改 debug 证书

把正式包改成 debug 证书签名，等同于换签名：手机上已装的旧包无法升级，必须先卸载，而 `allowBackup=false` 意味着卸载即清空数据。反过来把 debug 包刷成正式证书也做不到——私钥只在 CI 的 secrets 里，无法从已签名的 APK 反推。

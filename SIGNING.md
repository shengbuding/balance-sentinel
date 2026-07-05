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
  -storepass "balance-sentinel-release-2026" \
  -keypass "balance-sentinel-release-2026" \
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
| **Keystore 密码** | `balance-sentinel-release-2026` |
| **Key 密码** | `balance-sentinel-release-2026` |

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
export ANDROID_KEYSTORE_PASSWORD="balance-sentinel-release-2026"
export ANDROID_KEY_ALIAS="deepseek"
export ANDROID_KEY_PASSWORD="balance-sentinel-release-2026"
```

CI 脚本在构建前解码 keystore：
```bash
echo "$ANDROID_KEYSTORE_B64" | base64 -d > deepseek-balance.jks
```

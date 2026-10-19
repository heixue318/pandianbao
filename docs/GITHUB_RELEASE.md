# GitHub 测试升级流程

盘点宝通过公开 GitHub Release 检查更新，客户端不保存 GitHub Token。尚未配置远程仓库时，应用不会自行上传代码或发布安装包。

## 首次配置

1. 在 GitHub 创建仓库，例如 `fengluanchui/pandianbao`，再把仓库地址提供给开发环境以配置 Git remote。
2. 在应用的“更新”页填入 `owner/repo`，例如 `fengluanchui/pandianbao`。
3. 生成用于测试或正式发布的 APK。后续所有同设备升级包都必须使用同一签名密钥；签名归属为“风乱吹”。密钥文件只保存在本机安全位置，不能提交。

## 每次发布

1. 增加 `versionCode` 和 `versionName`，例如从 `1.0.0` 升到 `1.0.1`。
2. 在 GitHub 新建 Release，标签建议使用 `v1.0.1`。
3. 上传一个 APK 资源，例如 `PandianBao-v1.0.1.apk`，并发布该 Release。
4. GitHub 会为 Release 资源提供 SHA-256 digest。盘点宝只接受有此 digest 的 APK，下载完成后还会核对资源大小、应用包名和签名，之后才交给系统安装器。

测试期间可用 debug 签名在同一设备连续升级；正式包改用“风乱吹”的固定发布密钥后，不能与 debug 包互相覆盖安装，需要先卸载旧的 debug 包或保持同一签名策略。

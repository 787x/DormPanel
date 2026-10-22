import { ensure } from './protocol.mjs';
// 连接仅由应用内管理。安装、升级、目录授权回调均不消费旧 wizard 环境，
// 也不重写 legacy webdav.json；启动时由既有 catalog 迁移逻辑读取旧配置。
try {
  ensure(process.env.TRIM_PKGETC, '缺少配置目录');
} catch {
  process.exitCode = 1;
}

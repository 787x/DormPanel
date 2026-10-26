// Daily status only. Protocol evidence remains available in management/history.
export function scheduleStatus(s) {
  const messages = {
    OBSERVED: '已开启共享 · 最近一次服务器检查成功。',
    PUBLISHED: '已开启共享 · 本次发布已由服务器回读确认。',
    CONFLICT: '有修改冲突；双方内容已保留，请在管理中查看并处理。',
    RECOVERY_REQUIRED: '需要恢复未完成的操作；已停止写入，请进入管理处理。',
    DISCOVERING: '本轮检查尚未完成，进度已保存，请继续同步。',
    BUDGET: '本轮检查尚未完成，进度已保存，请继续同步。',
    ROTATED: '共享历史整理已完成，请继续同步检查。',
    unbound: '尚未开启共享，可使用本机课表；请在数据源管理中设置连接。',
    unchecked: '服务器状态待检查。',
    empty: '指定位置未发现旧版课表；尚未加入或创建。',
    verified: '最近一次服务器检查成功。',
    incomplete: '部分历史尚未验证，请继续检查；本机课表仍可使用。',
    branches: '有多个版本等待比较，请继续同步。',
    conflict: '有修改冲突，请在管理中查看并处理。',
    error: '连接或同步检查失败；本机数据保留，请检查连接后重试。',
  };
  return (messages[s.status] ?? '无法确定共享状态，请进入管理检查。') + (s.status === 'RECOVERY_REQUIRED' ? '\n本机数据与待确认记录保留' : '\n本机课表已保存') +
    (s.pending ? ' · 有操作待服务器回读确认' : s.dirty ? ' · 有修改待发布' : '') +
    (s.writeProtection ? '\n' + s.writeProtection : '');
}

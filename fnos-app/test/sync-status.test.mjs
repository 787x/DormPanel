import test from 'node:test';
import assert from 'node:assert/strict';
import { scheduleStatus } from '../web/sync-status.js';

test('正常共享只说明服务器事实，无恒定技术警告或全设备确认', () => {
  for (const status of ['OBSERVED', 'PUBLISHED', 'verified']) {
    const text = scheduleStatus({ status, protocol: 'v3', published: true });
    assert.match(text, /服务器/);
    assert.doesNotMatch(text, /Epoch|Writer|Descriptor|DAG|未知|全部同步|所有设备|离线/);
  }
});
test('待发布、待回读、冲突、网络及恢复事实分别呈现', () => {
  assert.match(scheduleStatus({ status: 'OBSERVED', dirty: true }), /待发布/);
  assert.match(scheduleStatus({ status: 'OBSERVED', pending: true, dirty: true }), /待服务器回读确认/);
  for (const status of ['CONFLICT', 'conflict']) assert.match(scheduleStatus({ status }), /冲突.*管理/);
  assert.match(scheduleStatus({ status: 'error' }), /失败.*重试/);
  assert.match(scheduleStatus({ status: 'RECOVERY_REQUIRED' }), /恢复.*停止写入/);
  assert.doesNotMatch(scheduleStatus({ status: 'RECOVERY_REQUIRED' }), /课表已保存/);
  assert.match(scheduleStatus({ status: 'unbound' }), /尚未开启共享/);
  assert.doesNotMatch(scheduleStatus({ status: 'unbound' }), /离线/);
});
test('部分验证、预算与真实写保护不误报完成', () => {
  for (const status of ['DISCOVERING', 'BUDGET', 'incomplete']) {
    const text = scheduleStatus({ status, writeProtection: '管理员已禁止写入' });
    assert.match(text, /未|尚未/); assert.match(text, /管理员已禁止写入/);
    assert.doesNotMatch(text, /检查成功|全部同步完成/);
  }
});

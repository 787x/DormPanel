"""开发用：从脱敏 CSV 重建真实 BIFF8 fixture（需要 xlwt，仅测试生成工具）。"""
from pathlib import Path
import csv
import xlwt

root = Path(__file__).resolve().parents[1]
fixture = root / 'schedule-core/src/test/resources/schedule/hubei_2026-2027-1'
with fixture.with_suffix('.csv').open(encoding='utf-8-sig', newline='') as source:
    rows = list(csv.reader(source))
workbook = xlwt.Workbook()
workbook.add_sheet('空表')
sheet = workbook.add_sheet('课表')
other = workbook.add_sheet('附表')
# 足够长的 Unicode 字符串覆盖 SST CONTINUE；不属于课程数据。
other.write(0, 0, '附加Unicode测试' * 1000)
sheet.write_merge(0, 0, 0, 7, rows[0][0])
for r, row in enumerate(rows[1:], 1):
    for c, value in enumerate(row):
        if value:
            sheet.write(r, c, value)
workbook.save(str(fixture.with_suffix('.xls')))

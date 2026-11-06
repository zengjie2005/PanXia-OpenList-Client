#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
列表点击通道回归自检（不依赖 Android，直接在 JDK/Python 环境跑）。

背景：AbsListView 在 ACTION_UP 只对 !itemView.hasFocusable() 的行执行
performItemClick。若行布局里存在 focusable=true 的子控件（例如右侧 ⋮ 按钮），
且根布局没有 descendantFocusability="blocksDescendants"，整个列表的
onItemClick 会完全不回调 —— 表现为「顶栏/底栏按钮正常，文件一行都点不动」。

检查项：
  1. 每个 item_*.xml 根布局必须阻断后代焦点，或布局内不存在可获焦控件
  2. 行布局不得出现 focusable="true" / focusableInTouchMode="true"
  3. FileAdapter 必须提供行内点击通道（v.setOnClickListener + setOnRow 回调）
  4. MainActivity 不得只依赖 setOnItemClickListener
  5. VideoActivity 手势必须消费 ACTION_DOWN，并提供横向拖动快进/快退
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
LAYOUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'layout')
JAVA = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'zhj', 'panxia')

results = []


def check(name, ok, detail):
    results.append((name, ok, detail))


rows = sorted(glob.glob(os.path.join(LAYOUT, 'item_*.xml')))
check('存在行布局', len(rows) > 0, '找到 %d 个' % len(rows))

for path in rows:
    name = os.path.basename(path)
    xml = io.open(path, encoding='utf-8').read()
    # 根标签的属性块（第一个开始标签内）
    m = re.search(r'<[A-Za-z][A-Za-z0-9_.]*\s+([^>]*?)/?>', xml, re.S)
    root_attrs = m.group(1) if m else ''
    blocked = 'descendantFocusability="blocksDescendants"' in root_attrs
    focusable = re.findall(r'android:focusable="true"', xml)
    focus_touch = re.findall(r'android:focusableInTouchMode="true"', xml)
    check('%s 根阻断后代焦点' % name, blocked,
          'descendantFocusability=blocksDescendants' if blocked else '缺失')
    check('%s 无可获焦子控件' % name, not focusable and not focus_touch,
          'focusable=true x%d, focusableInTouchMode=true x%d'
          % (len(focusable), len(focus_touch)))

adapter = io.open(os.path.join(JAVA, 'FileAdapter.java'), encoding='utf-8').read()
check('FileAdapter 行内点击通道',
      'v.setOnClickListener' in adapter and 'setRowListener' in adapter,
      'v.setOnClickListener + setRowListener')
check('FileAdapter 强制阻断焦点',
      'FOCUS_BLOCK_DESCENDANTS' in adapter and 'setFocusable(false)' in adapter,
      '代码层双保险')
check('FileAdapter 不使用 OnTouchListener 抢事件',
      'setOnTouchListener' not in adapter, '无行内 OnTouchListener')

main = io.open(os.path.join(JAVA, 'MainActivity.java'), encoding='utf-8').read()
check('MainActivity 使用行内监听',
      'setRowListener' in main and 'setOnItemClickListener' not in main,
      'adapter.setRowListener')


# ---- 播放器手势通道 ----
video = io.open(os.path.join(JAVA, 'VideoActivity.java'), encoding='utf-8').read()
m = re.search(r'public boolean onDown\(MotionEvent e\)\s*\{(.*?)\n            \}', video, re.S)
check('VideoActivity 消费 ACTION_DOWN',
      m is not None and 'return true' in m.group(1),
      'onDown() 返回 true（否则单击/双击/拖动全部收不到）')
check('VideoActivity videoBox 可点击兜底',
      'box.setClickable(true)' in video, 'box.setClickable(true)')
check('VideoActivity 单击可呼出控制栏',
      'setControls(!controlsVisible)' in video, 'onSingleTapConfirmed → setControls')
check('VideoActivity 横向拖动跳转',
      'public boolean onScroll(' in video and 'dragTarget' in video,
      'onScroll 换算目标时间')
check('VideoActivity 松手才生效、取消不跳转',
      'finishDragSeek(action == MotionEvent.ACTION_UP)' in video
      and 'finishDragSeek(boolean apply)' in video,
      'ACTION_UP 应用 / ACTION_CANCEL 放弃')
check('VideoActivity 拖动中不自动隐藏控制栏',
      'ui.removeCallbacks(hideControls)' in video, 'onScroll 暂停隐藏计时')

failed = [r for r in results if not r[1]]
for name, ok, detail in results:
    print('[%s] %-42s %s' % ('PASS' if ok else 'FAIL', name, detail))
print('-' * 60)
print('RESULT: %d passed, %d failed' % (len(results) - len(failed), len(failed)))
sys.exit(1 if failed else 0)

#!/usr/bin/env python3
"""
批量将核心交互区文件中的 TouchableOpacity 替换为 FocusableTouchableOpacity。
- 从 react-native import 中移除 TouchableOpacity
- 添加 import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'
- 不修改组件使用代码，只改 import 来源
"""
import re
import sys
from pathlib import Path

# 需要替换的目录/文件
TARGETS = [
    'src/screens/Home/Horizontal/Aside.tsx',
    'src/screens/Home/Views/Mylist',
    'src/screens/Home/Views/Search',
    'src/screens/Home/Views/Setting',
    'src/screens/Home/Views/Leaderboard',
    'src/screens/Home/Views/SongList',
    'src/screens/PlayDetail',
    'src/components/player',
    'src/components/OnlineList',
    'src/components/common/Menu.tsx',
]

PROJECT_ROOT = Path('/workspace/lx-music-tv')

def collect_files(targets):
    files = []
    for t in targets:
        p = PROJECT_ROOT / t
        if p.is_file() and p.suffix in ('.tsx', '.ts'):
            files.append(p)
        elif p.is_dir():
            files.extend(p.rglob('*.tsx'))
            files.extend(p.rglob('*.ts'))
    return sorted(set(files))

# 匹配: import { ..., TouchableOpacity, ... } from 'react-native'
IMPORT_RE = re.compile(
    r"import\s*\{([^}]*)\}\s*from\s*['\"]react-native['\"]",
    re.MULTILINE
)

NEW_IMPORT = "import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'"

def process_file(path: Path) -> bool:
    try:
        content = path.read_text(encoding='utf-8')
    except Exception as e:
        print(f"  SKIP {path}: {e}")
        return False

    if 'TouchableOpacity' not in content:
        return False

    # 检查是否已经替换过
    if "FocusableTouchableOpacity as TouchableOpacity" in content:
        return False

    # 检查是否从 react-native 导入了 TouchableOpacity
    changed = False

    def replace_import(m):
        nonlocal changed
        imports = m.group(1)
        # 分割 import 项
        items = [x.strip() for x in imports.split(',')]
        # 过滤掉 TouchableOpacity（纯名或带 type 前缀）
        new_items = [x for x in items if x and x != 'TouchableOpacity' and x != 'type TouchableOpacity' and x != 'TouchableOpacityProps' == False or (x not in ('TouchableOpacity',))]
        new_items = [x for x in items if x.strip() not in ('TouchableOpacity', 'type TouchableOpacity')]

        if len(new_items) == len(items):
            # 没有 TouchableOpacity，不改
            return m.group(0)

        changed = True
        if new_items:
            # 还有其他 react-native import
            return f"import {{{', '.join(new_items)}}} from 'react-native'"
        else:
            # react-native import 全空，删除整行
            return ''

    new_content = IMPORT_RE.sub(replace_import, content)

    if not changed:
        return False

    # 添加 FocusableTouchableOpacity import（放在第一个 import 之前或文件开头）
    # 找到第一个 import 行
    lines = new_content.split('\n')
    insert_idx = 0
    for i, line in enumerate(lines):
        if line.startswith('import '):
            insert_idx = i
            break

    # 如果 react-native import 被清空了，可能留下空行，清理掉连续空行
    lines.insert(insert_idx, NEW_IMPORT)
    new_content = '\n'.join(lines)

    # 清理可能的多余空行（连续2个以上空行变1个）
    new_content = re.sub(r'\n{3,}', '\n\n', new_content)

    path.write_text(new_content, encoding='utf-8')
    return True

def main():
    files = collect_files(TARGETS)
    print(f"Found {len(files)} files to check")
    modified = 0
    for f in files:
        rel = f.relative_to(PROJECT_ROOT)
        if process_file(f):
            print(f"  MODIFIED: {rel}")
            modified += 1
    print(f"\nDone. Modified {modified} files.")

if __name__ == '__main__':
    main()

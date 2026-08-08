#!/usr/bin/env python3
"""Claude Code のセッション記録（JSONL）を、読める形へ絞り込む。

生の記録は数十MBあり、そのままでは文脈に載らない。ツールの出力（ビルドログ・
UIダンプ・逆アセンブル結果）が大半を占めるためでもある。ここでは
「人が書いた指示」と「返した文章」だけを取り出し、必要なら検索で絞る。

使い方:
    # 会話だけを時系列で出す（既定。ツール出力は除く）
    python3 tools/read_session.py <session.jsonl>

    # 利用者の発言だけ（何を依頼されたかの一覧）
    python3 tools/read_session.py <session.jsonl> --user-only

    # 語で絞る（前後の文脈も出す）
    python3 tools/read_session.py <session.jsonl> --grep prefill --context 2

    # 実行したコマンドの一覧
    python3 tools/read_session.py <session.jsonl> --commands

セッション記録の場所:
    ~/.claude/projects/<プロジェクトのパスをハイフンにしたもの>/<session-id>.jsonl
"""
import argparse
import json
import sys


def text_of(message):
    """message.content から本文だけを取り出す。ツール呼び出しと結果は落とす。"""
    content = message.get("content")
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    for block in content:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "\n".join(part for part in parts if part)


def commands_of(message):
    """Bashツールへ渡したコマンドを取り出す。"""
    content = message.get("content")
    if not isinstance(content, list):
        return []
    found = []
    for block in content:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        if block.get("name") != "Bash":
            continue
        command = (block.get("input") or {}).get("command")
        if command:
            found.append(command)
    return found


def load(path):
    """(役割, 本文, コマンド一覧) の並びにする。"""
    entries = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            kind = record.get("type")
            if kind not in ("user", "assistant"):
                continue
            # サブエージェントの発言は本流と混ざるので除く。
            if record.get("isSidechain"):
                continue
            message = record.get("message") or {}
            body = text_of(message).strip()
            commands = commands_of(message)
            # ツール結果だけのuserレコードは本文が空になる。
            if not body and not commands:
                continue
            entries.append((kind, body, commands))
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", help="セッション記録のJSONL")
    parser.add_argument("--user-only", action="store_true", help="利用者の発言だけ")
    parser.add_argument("--commands", action="store_true", help="実行したコマンドの一覧")
    parser.add_argument("--grep", help="この語を含む発言だけ")
    parser.add_argument("--context", type=int, default=0, help="--grep の前後に出す件数")
    parser.add_argument("--max-chars", type=int, default=1200, help="1発言の最大文字数")
    args = parser.parse_args()

    entries = load(args.session)

    if args.commands:
        for _, _, commands in entries:
            for command in commands:
                print(command.strip().splitlines()[0][:200])
        return 0

    if args.user_only:
        entries = [entry for entry in entries if entry[0] == "user"]

    selected = range(len(entries))
    if args.grep:
        needle = args.grep.lower()
        hits = [i for i, (_, body, _) in enumerate(entries) if needle in body.lower()]
        if not hits:
            print(f"「{args.grep}」を含む発言は無い", file=sys.stderr)
            return 1
        wanted = set()
        for index in hits:
            for offset in range(-args.context, args.context + 1):
                if 0 <= index + offset < len(entries):
                    wanted.add(index + offset)
        selected = sorted(wanted)

    label = {"user": "利用者", "assistant": "Claude"}
    for index in selected:
        kind, body, _ = entries[index]
        if not body:
            continue
        shown = body if len(body) <= args.max_chars else body[: args.max_chars] + f"…（{len(body)}文字）"
        print(f"\n----- [{index}] {label.get(kind, kind)} -----")
        print(shown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

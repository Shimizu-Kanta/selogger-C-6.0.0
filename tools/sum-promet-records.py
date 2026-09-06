#!/usr/bin/env python3
"""promet の trace.json を読み、record の合計が size を超えていないかを確認する。

使い方:
    python tools/sum-promet-records.py <trace.json> [--size L]

出力:
    events   : 出力されたイベント種類数 n
    sum_freq : Sigma freq（累積発生回数）
    sum_rec  : Sigma record（保持件数）
    max_rec  : max_i record_i
    rate     : 保存率 sum_rec / sum_freq
    --size を指定すると sum_rec <= L を判定し、超えていたら終了コード 1 を返す。
"""

import argparse
import json
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", help="promet が出力した trace.json")
    parser.add_argument("--size", type=int, default=None,
                        help="全体容量 L。指定すると sum(record) <= L を検査する")
    args = parser.parse_args()

    with open(args.trace, encoding="utf-8") as f:
        data = json.load(f)

    if data.get("format") != "promet":
        print("warning: format is %r, not 'promet'" % data.get("format"), file=sys.stderr)

    events = data.get("events", [])
    sum_freq = 0
    sum_rec = 0
    max_rec = 0
    zero_cap_reached = data.get("zeroCapReached")

    for e in events:
        freq = e.get("freq", 0)
        rec = e.get("record", 0)
        sum_freq += freq
        sum_rec += rec
        max_rec = max(max_rec, rec)

        # value/seqnum/thread の長さは record と一致していなければならない。
        for key in ("value", "seqnum", "thread"):
            if key in e and len(e[key]) != rec:
                print("error: %s length %d != record %d at line %s"
                      % (key, len(e[key]), rec, e.get("line")), file=sys.stderr)
                return 1

    print("events   : %d" % len(events))
    print("sum_freq : %d" % sum_freq)
    print("sum_rec  : %d" % sum_rec)
    print("max_rec  : %d" % max_rec)
    print("rate     : %.4f%%" % (100.0 * sum_rec / sum_freq if sum_freq else 0.0))
    if zero_cap_reached is not None:
        print("zeroCap  : %s" % zero_cap_reached)

    if args.size is not None:
        if sum_rec > args.size:
            print("FAIL: sum(record)=%d exceeds size=%d" % (sum_rec, args.size))
            return 1
        print("OK: sum(record)=%d <= size=%d" % (sum_rec, args.size))
    return 0


if __name__ == "__main__":
    sys.exit(main())

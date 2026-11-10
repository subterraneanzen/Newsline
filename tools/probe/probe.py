import sys, urllib.request, re, ssl
for u in open(sys.argv[1]).read().split():
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) NewsArchive/1.0", "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"}), timeout=25)
        b = r.read(3_000_000).decode("utf-8", "replace")
        n = len(re.findall(r"<item[\s>]|<entry[\s>]", b))
        t = re.findall(r"<(?:item|entry)[\s>].*?<title[^>]*>(.*?)</title>", b, re.S)[:1]
        d = re.findall(r"<(?:pubDate|updated|published|dc:date)>(.*?)<", b)[:2]
        print(f"OK {r.status} items={n} final={r.geturl()} | {t[0].strip()[:90] if t else '-'} | {d}")
    except Exception as e:
        print(f"FAIL {u} :: {str(e)[:120]}")

import sys, urllib.request, re, json, html
UA={"User-Agent":"Mozilla/5.0 (Linux; Android 14) NewsArchive/1.0","Accept":"*/*"}
def get(u):
    r=urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=25); return r.geturl(), r.read(4_000_000).decode("utf-8","replace")
VID=re.compile(r"/videos?/|/video-|/watch|youtube\.com|youtu\.be|/player/|/clip/|/media/video|/live-?video", re.I)
for u in open(sys.argv[1]).read().split():
    try:
        _, b = get(u)
        items = re.findall(r"<(?:item|entry|url)[\s>].*?</(?:item|entry|url)>", b, re.S)
        links=[]
        for it in items:
            m = re.search(r"<link[^>]*href=\"([^\"]+)\"", it) or re.search(r"<link>\s*(?:<!\[CDATA\[)?\s*(\S+?)\s*(?:\]\]>)?\s*</link>", it) or re.search(r"<loc>(.*?)</loc>", it)
            t = re.search(r"<(?:title|news:title)[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</(?:title|news:title)>", it, re.S)
            if m: links.append((html.unescape(m.group(1).strip()), html.unescape(t.group(1).strip()) if t else ""))
        vids=[l for l in links if VID.search(l[0]) or re.match(r"\s*(watch|video)\b", l[1], re.I)]
        print(f"FEED {u} items={len(links)} video_like={len(vids)}")
        for v in vids[:4]: print(f"   VID {v[0][:110]} | {v[1][:70]}")
        # inspect first 6 article pages
        for link,title in links[:6]:
            try:
                fu, p = get(link)
                acc = re.findall(r'"isAccessibleForFree"\s*:\s*"?(\w+)', p)[:2]
                tier = re.findall(r'content_tier"?\s*content="([^"]+)"', p)[:1] + re.findall(r'"(?:contentTier|content_tier|accessLevel|paywallType|premium)"\s*:\s*"?([\w-]+)', p, re.I)[:2]
                ogt = re.findall(r'property="og:type"\s+content="([^"]+)"', p)[:1]
                ldtypes = sorted(set(re.findall(r'"@type"\s*:\s*"(NewsArticle|VideoObject|Article|ReportageNews|LiveBlogPosting|AnalysisNewsArticle)"', p)))
                hints = [h for h in ["subscriber-only","Subscribe to continue","subscriber_only","paywall","piano","tp-container","meter","premium","Subscribers only","This article is for subscribers"] if h.lower() in p.lower()]
                paras = len(re.findall(r"<p[\s>]", p))
                print(f"   PAGE paras={paras} acc={acc} tier={tier} og={ogt} ld={ldtypes} hints={hints[:5]} :: {fu[:100]}")
            except Exception as e: print(f"   PAGEFAIL {link[:90]} {str(e)[:60]}")
    except Exception as e:
        print(f"FAIL {u} :: {str(e)[:120]}")

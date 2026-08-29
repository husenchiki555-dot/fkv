import json,re,sys,collections
src=sys.argv[1] if len(sys.argv)>1 else 'app/src/main/assets/topic_seeds.json'
out=sys.argv[2] if len(sys.argv)>2 else src
d=json.load(open(src,encoding='utf-8'))
if len(d)!=50: raise ValueError(f'expected 50 decks, got {len(d)}')
banned=(
 'talking about ','remembering ','recognizing ','explaining ','guessing ','thinking about ',
 'a clue about ','a picture of ','trying to describe ','favorite ','ordering ','serving ',
 'a person starts ','a person keeps '
)
action_decks={'Actions','Social Media','Travel'}
total=0
for t in d:
    if 'cards' not in t or 'seeds' in t: raise ValueError(f"{t.get('title')}: expected explicit cards only")
    cards=t['cards']; total+=len(cards)
    if len(cards)<80: raise ValueError(f"{t['title']}: only {len(cards)} cards")
    seen=set(); diffs=collections.Counter()
    for c in cards:
        x=c.get('text','').strip(); k=x.casefold(); diff=c.get('difficulty')
        if not x or len(x)>65 or '{' in x or '}' in x: raise ValueError(f"{t['title']}: malformed card {x!r}")
        if k in seen: raise ValueError(f"{t['title']}: duplicate {x!r}")
        if t['title'] not in action_decks and any(k.startswith(p) for p in banned): raise ValueError(f"{t['title']}: filler phrase {x!r}")
        if diff not in {'Easy','Normal','Hard'}: raise ValueError(f"{t['title']}: invalid difficulty {diff!r}")
        seen.add(k); diffs[diff]+=1
    if not all(diffs[x]>0 for x in ('Easy','Normal','Hard')): raise ValueError(f"{t['title']}: missing difficulty tier {dict(diffs)}")
json.dump(d,open(out,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
print(f'CONTENT AUDIT SUCCESS: {len(d)} decks, {total} explicit cards, no generated filler phrases, no duplicates within decks')

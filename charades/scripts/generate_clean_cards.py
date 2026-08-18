import json, re, sys
src=sys.argv[1] if len(sys.argv)>1 else 'app/src/main/assets/topic_seeds.json'
out=sys.argv[2] if len(sys.argv)>2 else src
D=json.load(open(src,encoding='utf-8'))
fix={"Stayin Alive":"Stayin' Alive","Wall-E":"WALL-E","Aespa":"aespa","Mini P.E.K.K.A":"Mini P.E.K.K.A."}
def X(s):
    s=fix.get(s,s).strip()
    if s and s[0].islower() and not any(c.isupper() for c in s[1:]): return s[0].upper()+s[1:]
    return s
def lower_x(s): return fix.get(s,s).strip()
T={
'Animals':['{X}','Baby {x}','Sleeping {x}','Hungry {x}','Angry {x}','{X} eating','{X} hiding','{X} in the wild','{X} chasing something','{X} trying to escape'],
'Food & Drinks':['{X}','Ordering {x}','Making {x}','Buying {x}','Sharing {x}','Serving {x}','Trying {x}','Favorite {x}','{X} at a restaurant','{X} at a party'],
'Movies':['{X}','Watching {x}','Recommending {x}','Movie poster for {x}','A scene from {x}','Quoting {x}','Soundtrack from {x}','Movie night with {x}','Talking about {x}','Acting in {x}'],
'TV Shows':['{X}','Watching {x}','Binge-watching {x}','An episode of {x}','Theme song from {x}','Recommending {x}','A character from {x}','Talking about {x}','Watching the finale of {x}','Explaining the plot of {x}'],
'Celebrities':['{X}','Meeting {x}','Interviewing {x}','Taking a selfie with {x}','Getting an autograph from {x}','Imitating {x}','Seeing {x} on TV','Talking about {x}','Walking a red carpet with {x}','Being a huge fan of {x}'],
'Songs':['{X}','Singing {x}','Dancing to {x}','Karaoke to {x}','Hearing {x} on the radio','Playing {x} too loudly','Requesting {x} at a party','Music video for {x}','Recognizing {x} instantly','Humming {x}'],
'Music Artists':['{X}','Listening to {x}','A concert by {x}','Meeting {x}','Taking a selfie with {x}','Getting an autograph from {x}','Singing like {x}','An interview with {x}','Being a huge fan of {x}','Seeing {x} on stage'],
'Football / Soccer':['{X}','Meeting {x}','Playing football with {x}','Taking a selfie with {x}','Getting an autograph from {x}','Celebrating like {x}','Watching {x} score','Wearing a {x} jersey','Trying to defend against {x}','Commentating on {x}'],
'Sports':['{X}','Carrying {x}','Buying {x}','Dropping {x}','Packing {x}','Losing {x}','Finding {x}','Practicing with {x}','Borrowing {x}','Putting away {x}'],
'Countries':['{X}','Visiting {x}','Flying to {x}','A map of {x}','The flag of {x}','Food from {x}','Souvenirs from {x}','Living in {x}','Learning about {x}','Planning a trip to {x}'],
'Cities & Landmarks':['{X}','Visiting {x}','Taking a photo of {x}','Traveling to {x}','Sightseeing at {x}','A map showing {x}','Talking about {x}','A postcard from {x}','Getting directions to {x}','Planning a trip to {x}'],
'Jobs':['{X}','Working as a {x}','Meeting a {x}','Dressed as a {x}','Pretending to be a {x}','Talking to a {x}','Watching a {x} work','Training to be a {x}','A very busy {x}','Explaining what a {x} does'],
'Everyday Objects':['{X}','Using {x}','Losing {x}','Finding {x}','Buying {x}','Carrying {x}','Dropping {x}','Cleaning {x}','Breaking {x}','Borrowing {x}'],
'Actions':['{X}','Someone is {x}','A friend is {x}','A child is {x}','Watching someone {x}','Seeing someone {x}','A person starts {x}','A person keeps {x}','Guess the action: {x}','Trying to describe {x}'],
'Emotions':['{X}','Feeling {x}','Showing {x}','Hiding {x}','Sudden {x}','Strong {x}','Pretending to feel {x}','Seeing {x} on someone’s face','Talking about {x}','Trying to hide {x}'],
'School':['{X}','At school: {x}','Talking about {x}','Thinking about {x}','Remembering {x}','Recognizing {x}','Explaining {x}','A clue about {x}','A picture related to {x}','Describing {x}'],
'Science':['{X}','Learning about {x}','Explaining {x}','Reading about {x}','A teacher explains {x}','A quiz question about {x}','A documentary about {x}','Talking about {x}','A clue about {x}','Trying to understand {x}'],
'Technology':['{X}','Using {x}','Talking about {x}','Recognizing {x}','Learning about {x}','A picture of {x}','A clue about {x}','Explaining {x}','Thinking about {x}','Describing {x}'],
'Brands':['{X}','Recognizing {x}','The {x} logo','An advertisement for {x}','Talking about {x}','Choosing {x}','Comparing {x} with another brand','{X} on a billboard','{X} in a commercial','A famous brand: {x}'],
'Internet & Memes':['{X}','Seeing {x} online','Talking about {x}','Recognizing {x}','Explaining {x}','Reacting to {x}','A clue about {x}','Remembering {x}','Thinking about {x}','Describing {x}'],
'YouTubers & Streamers':['{X}','Watching {x}','Meeting {x}','Taking a selfie with {x}','A video by {x}','A livestream by {x}','Talking about {x}','Being a fan of {x}','Getting an autograph from {x}','Seeing {x} go live'],
'Social Media':['{X}','Someone is {x}','A friend is {x}','Watching someone {x}','Seeing someone {x}','Talking about {x}','Recognizing the action: {x}','A clue about {x}','Guess the social-media action: {x}','Trying to explain {x}'],
'K-Pop':['{X}','Listening to {x}','A concert by {x}','A music video by {x}','Talking about {x}','Being a fan of {x}','Recognizing {x}','Seeing {x} on stage','Watching a performance by {x}','Recommending {x} to a friend'],
'2000s Pop Culture':['{X}','Remembering {x}','Talking about {x}','Recognizing {x}','A 2000s reference: {x}','Explaining {x}','A clue about {x}','Guessing {x}','Thinking about {x}','Nostalgia for {x}'],
'2010s Pop Culture':['{X}','Remembering {x}','Talking about {x}','Recognizing {x}','A 2010s reference: {x}','Explaining {x}','A clue about {x}','Guessing {x}','Thinking about {x}','Nostalgia for {x}'],
'2020s Pop Culture':['{X}','Remembering {x}','Talking about {x}','Recognizing {x}','A 2020s reference: {x}','Explaining {x}','A clue about {x}','Guessing {x}','Thinking about {x}','Describing {x}'],
'Books':['{X}','Reading {x}','Recommending {x}','The cover of {x}','Talking about {x}','A character from {x}','A movie adaptation of {x}','A quote from {x}','Explaining the plot of {x}','Looking for {x} in a bookstore'],
'Travel':['{X}','Someone is {x}','A traveler is {x}','A family is {x}','Watching someone {x}','Seeing a tourist {x}','Talking about {x}','Recognizing the action: {x}','Guess the travel action: {x}','Trying to explain {x}'],
'Nature':['{X}','Seeing {x}','Talking about {x}','Learning about {x}','A picture of {x}','Recognizing {x}','A documentary about {x}','A clue about {x}','Reading about {x}','Describing {x}'],
'Fashion':['{X}','Buying {x}','Choosing {x}','Trying on {x}','Packing {x}','Losing {x}','Finding {x}','Borrowing {x}','Shopping for {x}','Showing {x} to a friend'],
'Slang & Phrases':['{X}','Saying “{x}”','Hearing “{x}”','Texting “{x}”','A friend says “{x}”','Explaining “{x}”','Reacting to “{x}”','Seeing “{x}” online','Using the phrase “{x}”','Recognizing “{x}”'],
'Clash Royale':['{X}','Playing {x}','Defending against {x}','Seeing {x} in the arena','Putting {x} in a deck','Countering {x}','Leveling up {x}','Losing a tower to {x}','Predicting {x}','Building a deck around {x}'],
'Pokémon':['{X}','Catching {x}','Training {x}','Battling with {x}','Seeing {x} in the wild','Drawing {x}','Recognizing {x}','A picture of {x}','Choosing {x} for a battle','Trying to catch {x}'],
'Minecraft':['{X}','Seeing {x} in Minecraft','Looking for {x} in Minecraft','Talking about {x}','Recognizing {x}','A clue about {x}','Drawing {x}','Guessing {x}','A Minecraft screenshot of {x}','Encountering {x} in Minecraft'],
'Roblox':['{X}','Seeing {x} on Roblox','Talking about {x}','Recognizing {x}','A clue about {x}','Explaining {x}','Guessing {x}','A Roblox screenshot of {x}','Remembering {x}','Hearing about {x}'],
'Holidays':['{X}','Talking about {x}','Remembering {x}','Recognizing {x}','A clue about {x}','Explaining {x}','Guessing {x}','Thinking about {x}','A holiday reference: {x}','Describing {x}'],
'Random Mix':['{X}','A clue for {x}','Talking about {x}','Recognizing {x}','A picture of {x}','Explaining {x}','Guessing {x}','Remembering {x}','Seeing {x}','Trying to describe {x}']}
franchise=['Marvel','DC','Disney & Pixar','Anime','Cartoons','Harry Potter','Star Wars','Fantasy']
f=['{X}','A clue about {x}','Talking about {x}','Recognizing {x}','Remembering {x}','Explaining {x}','Guessing {x}','A scene involving {x}','Seeing {x} in the story','Thinking about {x}']
for k in franchise:T[k]=f
characters=['Superheroes','Villains','Famous People & History','Mythology']
c=['{X}','Meeting {x}','Talking about {x}','Recognizing {x}','A picture of {x}','Imitating {x}','Reading about {x}','A clue about {x}','Explaining who {x} is','Guessing {x}']
for k in characters:T[k]=c
T['Video Games']=['{X}','Playing {x}','Watching {x} gameplay','Winning at {x}','Losing at {x}','Talking about {x}','Recommending {x}','A streamer playing {x}','Learning how to play {x}','Staying up late playing {x}']
titles=[t['title'] for t in D]
missing=[x for x in titles if x not in T]; extra=[x for x in T if x not in titles]
if missing or extra: raise ValueError(f'template coverage missing={missing} extra={extra}')
clean=[]
for t in D:
    seeds=[fix.get(s,s).strip() for s in t['seeds']]
    if len(seeds)!=20 or len(set(s.casefold() for s in seeds))!=20: raise ValueError(f"{t['title']}: expected 20 unique seeds")
    templates=T[t['title']]
    if len(templates)!=10: raise ValueError(f"{t['title']}: expected 10 reviewed forms")
    cards=[]
    for ti,tmpl in enumerate(templates):
        diff='Easy' if ti<4 else ('Normal' if ti<8 else 'Hard')
        for seed in seeds:
            text=re.sub(r'\s+',' ',tmpl.replace('{X}',X(seed)).replace('{x}',lower_x(seed))).strip()
            cards.append({'text':text,'difficulty':diff})
    if len(cards)!=200 or len({c['text'].casefold() for c in cards})!=200: raise ValueError(f"{t['title']}: card count/duplicate audit failed")
    clean.append({k:t[k] for k in ('id','title','emoji','age','group')}|{'cards':cards})
allcards=[c['text'] for t in clean for c in t['cards']]
known_bad=['using taco','cleaning bubble tea','pretending to be titanic','meeting the matrix','dressing like avatar']
issues=[x for x in allcards if not x.strip() or '  ' in x or '{x}' in x.lower() or len(x)>90 or any(b in x.casefold() for b in known_bad)]
if len(clean)!=50 or len(allcards)!=10000 or issues: raise ValueError(f'full audit failed: topics={len(clean)} cards={len(allcards)} issues={issues[:10]}')
json.dump(clean,open(out,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
print('CONTENT AUDIT SUCCESS: 50 topics, 10000 cards, 200/topic, 0 duplicates, 0 malformed/known-bad patterns')

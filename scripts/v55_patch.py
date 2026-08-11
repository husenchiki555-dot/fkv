from pathlib import Path

# v5.5: keep visual capture alive even if audio capture fails.
p=Path('app/src/main/java/com/huseyn/elixircollector/AutoCaptureService.java')
s=p.read_text()
s=s.replace('startVirtualDisplay();audioEngine.start(projection);', 'startVirtualDisplay();try{audioEngine.start(projection);}catch(RuntimeException audioError){prefs.edit().putBoolean(K_AUDIO_AVAILABLE,false).putString(K_STATUS,"AUDIO UNAVAILABLE • VISUAL ACTIVE").apply();}')
s=s.replace('RoyaleVision Auto v5.3','RoyaleVision Auto v5.5')
p.write_text(s)

# v5.5: geometry measured from the supplied 1080x2400 gameplay footage.
p=Path('app/src/main/java/com/huseyn/elixircollector/FrameAnalyzer.java')
s=p.read_text()
# Add dedicated Elixir profiles matching the real bar around x=.17-.80, y=.95-.995.
s=s.replace('double[][] profiles = {', 'double[][] profiles = {\n                {0.15, 0.80, 0.945, 0.998},\n                {0.17, 0.82, 0.952, 0.998},\n                {0.18, 0.84, 0.938, 0.995},')
# Replace v5.4 hand search with wider/lower profiles, including the measured hand row.
start=s.index('    private HandReading readHand(PlaneReader p, int w, int h) {')
end=s.index('    private ArenaReading readArena(PlaneReader p, int w, int h) {', start)
new='''    private HandReading readHand(PlaneReader p, int w, int h) {\n        double[] tops={0.82,0.84,0.86,0.88,0.90};\n        double[][] centersProfiles={\n                {0.24,0.40,0.55,0.70},\n                {0.26,0.415,0.565,0.715},\n                {0.29,0.44,0.59,0.74},\n                {0.33,0.48,0.63,0.78}\n        };\n        HandReading best=null;double bestScore=-1;\n        for(double top:tops)for(double[] centers:centersProfiles){HandReading r=readHandAt(p,w,h,top,centers);double score=r.validSlots+r.texture*4.0;if(score>bestScore){bestScore=score;best=r;}}\n        return best==null?readHandAt(p,w,h,0.88,new double[]{0.24,0.40,0.55,0.70}):best;\n    }\n\n    private HandReading readHandAt(PlaneReader p,int w,int h,double top,double[] centers){\n        double bottom=Math.min(0.985,top+0.095);double[][] hashes=new double[HAND_SLOTS][HASH_VALUES];double textureSum=0,maxDelta=0;int changedSlots=0,validSlots=0;\n        for(int slot=0;slot<HAND_SLOTS;slot++){double cx=centers[slot],left=cx-0.055,right=cx+0.055;int pos=0,count=0;double mean=0,meanSq=0;\n            for(int gy=0;gy<HASH_H;gy++)for(int gx=0;gx<HASH_W;gx++){int x=(int)(w*(left+(gx+0.5)/HASH_W*(right-left)));int y=(int)(h*(top+(gy+0.5)/HASH_H*(bottom-top)));int rgb=p.rgb(clamp(x,0,w-1),clamp(y,0,h-1));double rr=((rgb>>16)&255)/255.0,gg=((rgb>>8)&255)/255.0,bb=(rgb&255)/255.0;hashes[slot][pos++]=rr;hashes[slot][pos++]=gg;hashes[slot][pos++]=bb;double lum=(rr+gg+bb)/3.0;mean+=lum;meanSq+=lum*lum;count++;}\n            mean/=Math.max(1,count);meanSq/=Math.max(1,count);double texture=Math.sqrt(Math.max(0,meanSq-mean*mean));textureSum+=texture;if(texture>=0.024)validSlots++;\n            if(handInitialized){double delta=0;for(int i=0;i<HASH_VALUES;i++)delta+=Math.abs(hashes[slot][i]-previousHand[slot][i]);delta/=HASH_VALUES;if(delta>=0.105)changedSlots++;maxDelta=Math.max(maxDelta,delta);}\n        }\n        return new HandReading(hashes,textureSum/HAND_SLOTS,maxDelta,changedSlots,validSlots);\n    }\n\n'''
s=s[:start]+new+s[end:]
p.write_text(s)

# Calibrated recognizer: use the same real hand geometry.
p=Path('app/src/main/java/com/huseyn/elixircollector/HandCardRecognizer.java')
s=p.read_text()
start=s.index('    public Result recognize(Image image){')
end=s.index('    public static String inferPlayedCard', start)
method='''    public Result recognize(Image image){\n        String[] empty={null,null,null,null};double[] emptyC={0,0,0,0};if(image==null||image.getPlanes().length==0||deck.size()!=8||refs.size()<5)return new Result(empty,emptyC);Frame f=new Frame(image);if(!f.valid())return new Result(empty,emptyC);\n        double[] tops={0.82,0.84,0.86,0.88,0.90};double[][] profiles={{0.24,0.40,0.55,0.70},{0.26,0.415,0.565,0.715},{0.29,0.44,0.59,0.74},{0.33,0.48,0.63,0.78}};Result bestR=new Result(empty,emptyC);double bestScore=-1;\n        for(double top:tops)for(double[] centers:profiles){Result r=recognizeAt(f,top,centers);double score=0;for(int i=0;i<4;i++)score+=r.confidence[i]+(r.slotIds[i]!=null?0.35:0);if(score>bestScore){bestScore=score;bestR=r;}}return bestR;\n    }\n\n    private Result recognizeAt(Frame f,double top,double[] centers){String[] ids={null,null,null,null};double[] conf={0,0,0,0};double bottom=Math.min(0.975,top+0.075);for(int s=0;s<4;s++){double[] q=featureFrame(f,centers[s]-0.045,top,centers[s]+0.045,bottom);String bestId=null;double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;for(String id:deck){List<double[]> variants=refs.get(id);if(variants==null)continue;double d=Double.POSITIVE_INFINITY;for(double[] r:variants)d=Math.min(d,distance(q,r));if(d<best){second=best;best=d;bestId=id;}else if(d<second)second=d;}double margin=Math.max(0,second-best);double c=clamp(1.0-best/0.58,0,1)*0.68+clamp(margin/0.08,0,1)*0.32;if(bestId!=null&&c>=0.36){ids[s]=bestId;conf[s]=c;}}return new Result(ids,conf);}\n\n'''
s=s[:start]+method+s[end:]
p.write_text(s)

# Version bump.
p=Path('app/build.gradle');s=p.read_text().replace("versionCode 540","versionCode 550").replace("versionName '5.4.0'","versionName '5.5.0'");p.write_text(s)

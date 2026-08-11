package com.huseyn.elixircollector;

import java.util.HashMap;
import java.util.Map;

/** Session-local audio prototypes learned from the user's own calibrated card plays. */
public final class AudioProfileBank {
    private static final class Profile {
        double strength,zcr,brightness;int n;
        void add(AudioEvidenceEngine.Fingerprint f){
            double a=n<=0?1.0:Math.min(0.45,1.0/(n+1.0));
            if(n==0){strength=f.strength;zcr=f.zcr;brightness=f.brightness;}else{
                strength=strength*(1-a)+f.strength*a;zcr=zcr*(1-a)+f.zcr*a;brightness=brightness*(1-a)+f.brightness*a;
            }n++;
        }
        AudioEvidenceEngine.Fingerprint asFingerprint(){return new AudioEvidenceEngine.Fingerprint(0,strength,zcr,brightness);}
    }
    private final Map<String,Profile> profiles=new HashMap<>();
    public void clear(){profiles.clear();}
    public void learn(String cardId,AudioEvidenceEngine.Fingerprint f){if(cardId==null||f==null)return;Profile p=profiles.get(cardId);if(p==null){p=new Profile();profiles.put(cardId,p);}p.add(f);}
    public String match(AudioEvidenceEngine.Fingerprint f,int cost){
        if(f==null)return null;String bestId=null;double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
        for(Map.Entry<String,Profile> e:profiles.entrySet()){
            CardCatalog.Card c=find(e.getKey());if(c==null||(!c.mirror&&cost>0&&c.cost!=cost))continue;
            double d=AudioEvidenceEngine.distance(f,e.getValue().asFingerprint());
            if(d<best){second=best;best=d;bestId=e.getKey();}else if(d<second)second=d;
        }
        if(bestId==null)return null;
        double margin=second-best;
        return best<=1.15&&(Double.isInfinite(second)||margin>=0.18)?bestId:null;
    }
    private static CardCatalog.Card find(String id){for(CardCatalog.Card c:CardCatalog.ALL)if(c.id.equals(id))return c;return null;}
}

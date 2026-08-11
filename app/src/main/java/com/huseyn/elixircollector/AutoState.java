package com.huseyn.elixircollector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic opponent state for the local automatic-CV build. */
public final class AutoState {
    public static final double MAX_ELIXIR=10.0;
    public static final double BASE_SECONDS_PER_ELIXIR=2.8;

    public static final class Event {
        public final long timeMs;public final String kind;public final String deckId;public final String name;public final double delta;public final boolean cycleAdvance;
        public Event(long t,String k,String d,String n,double x,boolean c){timeMs=t;kind=k;deckId=d;name=n;delta=x;cycleAdvance=c;}
    }
    public static final class DeckStatus {
        public final String deckId,name;public final int cardsUntilReturn;public final double lastCost;public final int lastCycleIndex;
        DeckStatus(String d,String n,int u,double c,int i){deckId=d;name=n;cardsUntilReturn=u;lastCost=c;lastCycleIndex=i;}
        public boolean isInHandOrAvailable(){return cardsUntilReturn==0;}
    }

    private final ArrayList<Event> events=new ArrayList<>();
    private long matchClockStartMs,elixirAnchorMs;private double initialOpponentElixir=Double.NaN,observedLocalAtAnchor=Double.NaN;

    public synchronized void start(long clockStartMs,long anchorMs,double observedOpponentAtAnchor){
        matchClockStartMs=Math.max(1L,clockStartMs);elixirAnchorMs=Math.max(matchClockStartMs,anchorMs);initialOpponentElixir=clamp(observedOpponentAtAnchor,0,10);observedLocalAtAnchor=observedOpponentAtAnchor;events.clear();
    }
    public synchronized void start(long nowMs,double observedLocalElixir){start(nowMs,nowMs,observedLocalElixir);}
    public synchronized void reset(){matchClockStartMs=0;elixirAnchorMs=0;initialOpponentElixir=Double.NaN;observedLocalAtAnchor=Double.NaN;events.clear();}
    public synchronized boolean isStarted(){return matchClockStartMs>0&&elixirAnchorMs>0&&!Double.isNaN(initialOpponentElixir);}
    public synchronized long getMatchStartMs(){return matchClockStartMs;}public synchronized long getElixirAnchorMs(){return elixirAnchorMs;}public synchronized double getInitialOpponentElixir(){return initialOpponentElixir;}public synchronized double getObservedLocalAtAnchor(){return observedLocalAtAnchor;}

    public synchronized Event addCard(CardCatalog.Card card,long nowMs){
        if(!isStarted()||card==null)return null;double cost=card.cost;
        if(card.mirror){Event p=lastCycleEvent();if(p==null)return null;cost=Math.min(10,Math.max(0,-p.delta+1));}
        Event e=new Event(Math.max(elixirAnchorMs,nowMs),"CARD_COMMIT",card.deckId,card.displayName,-cost,true);events.add(e);return e;
    }
    public synchronized Event addCardId(String id,long nowMs){for(CardCatalog.Card c:CardCatalog.ALL)if(c.id.equals(id))return addCard(c,nowMs);return null;}
    /** Unknown identity still advances the hidden four-card rotation and spends Elixir. */
    public synchronized Event addUnknownCost(int cost,long nowMs){
        if(!isStarted()||cost<1||cost>10)return null;Event e=new Event(Math.max(elixirAnchorMs,nowMs),"CARD_COMMIT_UNKNOWN",null,"Unknown "+cost,-cost,true);events.add(e);return e;
    }
    public synchronized Event addSpend(double amount,long nowMs){if(!isStarted())return null;Event e=new Event(Math.max(elixirAnchorMs,nowMs),"RESOURCE_SPEND",null,"Ability −"+fmt(amount),-Math.abs(amount),false);events.add(e);return e;}
    public synchronized Event addGain(double amount,long nowMs){if(!isStarted())return null;Event e=new Event(Math.max(elixirAnchorMs,nowMs),"RESOURCE_GAIN",null,"Gain +"+fmt(amount),Math.abs(amount),false);events.add(e);return e;}
    public synchronized Event undo(){return events.isEmpty()?null:events.remove(events.size()-1);}public synchronized List<Event> getEvents(){return new ArrayList<>(events);}
    public synchronized void restore(long clockStartMs,long anchorMs,double initialElixir,List<Event> restored){matchClockStartMs=clockStartMs;elixirAnchorMs=anchorMs;initialOpponentElixir=initialElixir;observedLocalAtAnchor=initialElixir;events.clear();if(restored!=null){events.addAll(restored);events.sort(Comparator.comparingLong(e->e.timeMs));}}

    public synchronized double getOpponentElixir(long nowMs){
        if(!isStarted())return Double.NaN;double value=initialOpponentElixir;long cursor=elixirAnchorMs;
        for(Event e:events){long t=Math.max(cursor,e.timeMs);value=Math.min(MAX_ELIXIR,value+regen(cursor,t));value=clamp(value+e.delta,0,MAX_ELIXIR);cursor=t;}
        return Math.min(MAX_ELIXIR,value+regen(cursor,Math.max(cursor,nowMs)));
    }
    private double regen(long fromMs,long toMs){if(!isStarted()||toMs<=fromMs)return 0;double from=Math.max(0,(fromMs-matchClockStartMs)/1000.0),to=Math.max(from,(toMs-matchClockStartMs)/1000.0),total=0;total+=segment(from,to,0,120,1);total+=segment(from,to,120,240,2);total+=segment(from,to,240,300,3);return total;}
    private double segment(double from,double to,double a,double b,double m){double x=Math.max(from,a),y=Math.min(to,b);return y<=x?0:(y-x)*m/BASE_SECONDS_PER_ELIXIR;}
    public synchronized double getMultiplier(long nowMs){if(!isStarted())return 1;double e=Math.max(0,(nowMs-matchClockStartMs)/1000.0);return e<120?1:e<240?2:3;}
    public synchronized String getClock(long nowMs){if(!isStarted())return "SEARCHING";int elapsed=(int)Math.floor(Math.max(0,(nowMs-matchClockStartMs)/1000.0));if(elapsed<180){int r=180-elapsed;return String.format(Locale.US,"%d:%02d",r/60,r%60);}int ot=elapsed-180;if(ot<=120){int r=Math.max(0,120-ot);return String.format(Locale.US,"OT %d:%02d",r/60,r%60);}return "ENDED?";}
    private Event lastCycleEvent(){for(int i=events.size()-1;i>=0;i--)if(events.get(i).cycleAdvance)return events.get(i);return null;}
    public synchronized int getCycleCount(){int n=0;for(Event e:events)if(e.cycleAdvance)n++;return n;}

    public synchronized List<DeckStatus> getDeck(){
        LinkedHashMap<String,String> names=new LinkedHashMap<>();LinkedHashMap<String,Integer> lastIndex=new LinkedHashMap<>();LinkedHashMap<String,Double> costs=new LinkedHashMap<>();int index=0;
        for(Event e:events){if(!e.cycleAdvance)continue;if(e.deckId!=null){if(!names.containsKey(e.deckId))names.put(e.deckId,e.name);lastIndex.put(e.deckId,index);costs.put(e.deckId,-e.delta);}index++;}
        ArrayList<DeckStatus> out=new ArrayList<>();for(Map.Entry<String,String>x:names.entrySet()){int last=lastIndex.get(x.getKey()),since=index-1-last,until=Math.max(0,4-since);out.add(new DeckStatus(x.getKey(),x.getValue(),until,costs.get(x.getKey()),last));}return out;
    }
    public synchronized boolean hasDeckConflict(){return getDeck().size()>8;}
    public synchronized String getLast(){if(events.isEmpty())return "LAST: —";Event e=events.get(events.size()-1);return "LAST: "+e.name;}
    private static String fmt(double v){return Math.abs(v-Math.rint(v))<0.001?String.valueOf((int)Math.rint(v)):String.format(Locale.US,"%.1f",v);}private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}

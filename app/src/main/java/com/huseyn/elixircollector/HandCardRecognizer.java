package com.huseyn.elixircollector;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Very small on-device hand recognizer restricted to the user's calibrated 8-card deck.
 * It compares low-resolution color features from each live hand slot with bundled card art.
 * This is only supporting evidence; low-confidence matches remain UNKNOWN.
 */
public final class HandCardRecognizer {
    public static final class Result {
        public final String[] slotIds;
        public final double[] confidence;
        Result(String[] ids,double[] conf){slotIds=ids;confidence=conf;}
    }

    private static final int GW=5, GH=6, FV=GW*GH*3;
    private final Context context;
    private final Map<String,double[]> refs=new HashMap<>();
    private List<String> deck=new ArrayList<>();

    public HandCardRecognizer(Context context){this.context=context.getApplicationContext();reloadDeck();}

    public void reloadDeck(){
        deck=DeckCalibrationActivity.loadDeck(context);
        refs.clear();
        for(String id:deck){
            Bitmap b=CardIconLoader.load(context,id);
            if(b!=null) refs.put(id,featureBitmap(b));
        }
    }

    public Result recognize(Image image){
        String[] ids={null,null,null,null};
        double[] conf={0,0,0,0};
        if(image==null||image.getPlanes().length==0||deck.size()!=8||refs.size()<5)return new Result(ids,conf);
        Frame f=new Frame(image); if(!f.valid())return new Result(ids,conf);
        double[] centers={0.365,0.515,0.665,0.815};
        for(int s=0;s<4;s++){
            double[] q=featureFrame(f,centers[s]-0.047,0.765,centers[s]+0.047,0.855);
            String bestId=null; double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
            for(String id:deck){
                double[] r=refs.get(id); if(r==null)continue;
                double d=distance(q,r);
                if(d<best){second=best;best=d;bestId=id;} else if(d<second)second=d;
            }
            double margin=Math.max(0.0,second-best);
            double c=clamp(1.0-best/0.42,0,1)*0.72+clamp(margin/0.10,0,1)*0.28;
            if(bestId!=null&&c>=0.53){ids[s]=bestId;conf[s]=c;}
        }
        return new Result(ids,conf);
    }

    public static String inferPlayedCard(Result previous,Result current){
        if(previous==null||current==null)return null;
        for(int i=0;i<4;i++){
            String id=previous.slotIds[i]; if(id==null||previous.confidence[i]<0.56)continue;
            boolean stillPresent=false;
            for(int j=0;j<4;j++) if(id.equals(current.slotIds[j])&&current.confidence[j]>=0.50){stillPresent=true;break;}
            if(!stillPresent)return id;
        }
        return null;
    }

    private static double[] featureBitmap(Bitmap b){
        double[] out=new double[FV];int p=0;
        for(int gy=0;gy<GH;gy++)for(int gx=0;gx<GW;gx++){
            int x=(int)(b.getWidth()*(0.18+(gx+0.5)/GW*0.64));
            int y=(int)(b.getHeight()*(0.14+(gy+0.5)/GH*0.58));
            int c=b.getPixel(clamp(x,0,b.getWidth()-1),clamp(y,0,b.getHeight()-1));
            out[p++]=((c>>16)&255)/255.0;out[p++]=((c>>8)&255)/255.0;out[p++]=(c&255)/255.0;
        }
        normalize(out);return out;
    }

    private static double[] featureFrame(Frame f,double l,double t,double r,double b){
        double[] out=new double[FV];int p=0;
        for(int gy=0;gy<GH;gy++)for(int gx=0;gx<GW;gx++){
            int x=(int)(f.width*(l+(gx+0.5)/GW*(r-l)));
            int y=(int)(f.height*(t+(gy+0.5)/GH*(b-t)));
            int rgb=f.rgb(x,y);out[p++]=((rgb>>16)&255)/255.0;out[p++]=((rgb>>8)&255)/255.0;out[p++]=(rgb&255)/255.0;
        }
        normalize(out);return out;
    }

    private static void normalize(double[] a){
        double m=0;for(double v:a)m+=v;m/=Math.max(1,a.length);
        double sd=0;for(double v:a){double d=v-m;sd+=d*d;}sd=Math.sqrt(sd/Math.max(1,a.length));sd=Math.max(0.08,sd);
        for(int i=0;i<a.length;i++)a[i]=(a[i]-m)/sd;
    }
    private static double distance(double[] a,double[] b){double s=0;for(int i=0;i<Math.min(a.length,b.length);i++){double d=a[i]-b[i];s+=d*d;}return Math.sqrt(s/Math.max(1,a.length));}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    private static final class Frame{
        final int width,height,rowStride,pixelStride,capacity;final ByteBuffer buffer;
        Frame(Image image){Image.Plane p=image.getPlanes()[0];width=image.getWidth();height=image.getHeight();buffer=p.getBuffer();rowStride=p.getRowStride();pixelStride=p.getPixelStride();capacity=buffer.capacity();}
        boolean valid(){return buffer!=null&&pixelStride>=4&&rowStride>0;}
        int rgb(int x,int y){x=clamp(x,0,width-1);y=clamp(y,0,height-1);int o=y*rowStride+x*pixelStride;if(o<0||o+2>=capacity)return 0;int r=buffer.get(o)&255,g=buffer.get(o+1)&255,b=buffer.get(o+2)&255;return (r<<16)|(g<<8)|b;}
    }
}

package com.huseyn.elixircollector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.Image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Classical CV detector for the small purple Elixir-cost badge shown on deployments. */
public final class CostBadgeDetector {
    public static final class Detection {
        public final int cost;
        public final double confidence;
        public final int x;
        public final int y;
        public final long timeMs;
        Detection(int cost,double confidence,int x,int y,long timeMs){this.cost=cost;this.confidence=confidence;this.x=x;this.y=y;this.timeMs=timeMs;}
    }

    private static final int NORM_W=16,NORM_H=24;
    private final List<DigitTemplate> templates=new ArrayList<>();
    private byte[] previousGray;
    private int previousW,previousH;
    private Detection lastAccepted;

    public CostBadgeDetector(){buildTemplates();}

    public void reset(){previousGray=null;previousW=previousH=0;lastAccepted=null;}

    public Detection detect(Image image,long nowMs){
        if(image==null||image.getPlanes().length==0)return null;
        Frame f=new Frame(image); if(!f.valid())return null;
        int w=f.width,h=f.height;
        int step=Math.max(3,Math.min(7,w/270));
        int left=(int)(w*0.025),right=(int)(w*0.975),top=(int)(h*0.065),bottom=(int)(h*0.765);
        int mw=Math.max(1,(right-left+step-1)/step),mh=Math.max(1,(bottom-top+step-1)/step),total=mw*mh;
        boolean[] purple=new boolean[total]; byte[] gray=new byte[total];
        for(int my=0;my<mh;my++){
            int y=Math.min(bottom-1,top+my*step);
            for(int mx=0;mx<mw;mx++){
                int x=Math.min(right-1,left+mx*step),idx=my*mw+mx;
                int r=f.r(x,y),g=f.g(x,y),b=f.b(x,y);
                purple[idx]=isBadgePurple(r,g,b);
                gray[idx]=(byte)((r*30+g*59+b*11)/100);
            }
        }
        byte[] prev=previousGray; boolean comparable=prev!=null&&previousW==mw&&previousH==mh;
        previousGray=gray;previousW=mw;previousH=mh;if(!comparable)return null;

        boolean[] visited=new boolean[total];int[] queue=new int[total];Detection best=null;
        for(int start=0;start<total;start++){
            if(!purple[start]||visited[start])continue;
            int head=0,tail=0;queue[tail++]=start;visited[start]=true;
            int area=0,minX=mw,maxX=0,minY=mh,maxY=0;long motionSum=0;
            while(head<tail){
                int idx=queue[head++],x=idx%mw,y=idx/mw;area++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                motionSum+=Math.abs((gray[idx]&255)-(prev[idx]&255));
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    if(dx==0&&dy==0)continue;int nx=x+dx,ny=y+dy;if(nx<0||nx>=mw||ny<0||ny>=mh)continue;
                    int ni=ny*mw+nx;if(purple[ni]&&!visited[ni]){visited[ni]=true;queue[tail++]=ni;}
                }
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;
            if(area<6||area>560||bw<3||bh<3||bw>40||bh>44)continue;
            double aspect=bw/(double)bh,fill=area/(double)(bw*bh),motion=motionSum/(double)area;
            if(aspect<0.30||aspect>2.05||fill<0.10||motion<8.0)continue;
            int padX=Math.max(step*2,bw*step/3),padY=Math.max(step*2,bh*step/3);
            Rect box=new Rect(clamp(left+minX*step-padX,0,w-1),clamp(top+minY*step-padY,0,h-1),clamp(left+(maxX+1)*step+padX,1,w),clamp(top+(maxY+1)*step+padY,1,h));
            DigitResult dr=recognize(f,box); if(dr==null||dr.confidence<0.52)continue;
            Detection d=new Detection(dr.digit,dr.confidence,(box.left+box.right)/2,(box.top+box.bottom)/2,nowMs);
            if(best==null||d.confidence>best.confidence)best=d;
        }
        if(best==null)return null;
        if(lastAccepted!=null&&best.cost==lastAccepted.cost&&nowMs-lastAccepted.timeMs<650){
            double dx=best.x-lastAccepted.x,dy=best.y-lastAccepted.y;if(Math.sqrt(dx*dx+dy*dy)<Math.min(w,h)*0.08)return null;
        }
        lastAccepted=best;return best;
    }

    private DigitResult recognize(Frame f,Rect box){
        int minX=box.right,minY=box.bottom,maxX=box.left-1,maxY=box.top-1,bright=0;
        int scan=Math.max(1,Math.min(box.width(),box.height())/42);
        for(int y=box.top;y<box.bottom;y+=scan)for(int x=box.left;x<box.right;x+=scan){
            if(isDigitWhite(f.r(x,y),f.g(x,y),f.b(x,y))){bright++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
        }
        if(bright<7||maxX<=minX||maxY<=minY)return null;
        int gw=maxX-minX+1,gh=maxY-minY+1;double aspect=gw/(double)gh;if(aspect>0.98||aspect<0.07)return null;
        boolean[] glyph=new boolean[NORM_W*NORM_H];int on=0;
        for(int oy=0;oy<NORM_H;oy++)for(int ox=0;ox<NORM_W;ox++){
            int x0=minX+ox*gw/NORM_W,x1=minX+Math.max(1,(ox+1)*gw/NORM_W);
            int y0=minY+oy*gh/NORM_H,y1=minY+Math.max(1,(oy+1)*gh/NORM_H);boolean found=false;
            for(int y=y0;y<=Math.min(maxY,y1)&&!found;y++)for(int x=x0;x<=Math.min(maxX,x1);x++)if(isDigitWhite(f.r(x,y),f.g(x,y),f.b(x,y))){found=true;break;}
            glyph[oy*NORM_W+ox]=found;if(found)on++;
        }
        if(on<12||on>glyph.length*0.72)return null;
        int bestDigit=-1;double best=0,second=0;
        for(DigitTemplate t:templates){double s=shiftedF1(glyph,t.mask);if(s>best){second=best;best=s;bestDigit=t.digit;}else if(s>second)second=s;}
        double margin=Math.max(0,best-second);double conf=Math.min(1.0,best*0.88+margin*1.45);
        return bestDigit<0?null:new DigitResult(bestDigit,conf);
    }

    private void buildTemplates(){
        Typeface[] faces={Typeface.DEFAULT_BOLD,Typeface.MONOSPACE,Typeface.create(Typeface.SERIF,Typeface.BOLD)};
        for(int d=1;d<=9;d++)for(Typeface face:faces)templates.add(new DigitTemplate(d,renderDigit(d,face)));
    }

    private boolean[] renderDigit(int digit,Typeface face){
        Bitmap b=Bitmap.createBitmap(56,76,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.TRANSPARENT);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);p.setTextSize(62f);p.setTypeface(face);p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm=p.getFontMetrics();float baseline=38f-(fm.ascent+fm.descent)/2f;c.drawText(String.valueOf(digit),28f,baseline,p);
        int minX=b.getWidth(),minY=b.getHeight(),maxX=-1,maxY=-1;
        for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++)if(Color.alpha(b.getPixel(x,y))>70){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
        boolean[] out=new boolean[NORM_W*NORM_H];if(maxX<=minX||maxY<=minY){b.recycle();return out;}
        int w=maxX-minX+1,h=maxY-minY+1;
        for(int oy=0;oy<NORM_H;oy++)for(int ox=0;ox<NORM_W;ox++){int sx=minX+ox*w/NORM_W,sy=minY+oy*h/NORM_H;out[oy*NORM_W+ox]=Color.alpha(b.getPixel(sx,sy))>70;}
        b.recycle();return out;
    }

    private static double shiftedF1(boolean[] a,boolean[] t){double best=0;for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++){int inter=0,ac=0,tc=0;for(int y=0;y<NORM_H;y++)for(int x=0;x<NORM_W;x++){boolean av=a[y*NORM_W+x];int tx=x-dx,ty=y-dy;boolean tv=tx>=0&&tx<NORM_W&&ty>=0&&ty<NORM_H&&t[ty*NORM_W+tx];if(av)ac++;if(tv)tc++;if(av&&tv)inter++;}double s=(ac+tc)==0?0:(2.0*inter)/(ac+tc);best=Math.max(best,s);}return best;}
    private static boolean isBadgePurple(int r,int g,int b){return r>=105&&b>=120&&g<=165&&r+b>=g*2+70&&Math.max(r,b)-g>=32;}
    private static boolean isDigitWhite(int r,int g,int b){int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),l=(r*30+g*59+b*11)/100;return l>=165&&max-min<=92;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    private static final class DigitResult{final int digit;final double confidence;DigitResult(int d,double c){digit=d;confidence=c;}}
    private static final class DigitTemplate{final int digit;final boolean[] mask;DigitTemplate(int d,boolean[] m){digit=d;mask=m;}}
    private static final class Frame{
        final int width,height;final ByteBuffer buffer;final int rowStride,pixelStride,capacity;
        Frame(Image image){Image.Plane p=image.getPlanes()[0];width=image.getWidth();height=image.getHeight();buffer=p.getBuffer();rowStride=p.getRowStride();pixelStride=p.getPixelStride();capacity=buffer.capacity();}
        boolean valid(){return buffer!=null&&pixelStride>=4&&rowStride>0;}
        int r(int x,int y){return ch(x,y,0);}int g(int x,int y){return ch(x,y,1);}int b(int x,int y){return ch(x,y,2);}
        int ch(int x,int y,int c){x=clamp(x,0,width-1);y=clamp(y,0,height-1);int o=y*rowStride+x*pixelStride+c;return o>=0&&o<capacity?(buffer.get(o)&255):0;}
    }
}

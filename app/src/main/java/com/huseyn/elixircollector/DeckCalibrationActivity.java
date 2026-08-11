package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One-time/whenever-needed calibration: tell RoyaleVision which 8 cards are yours. */
public final class DeckCalibrationActivity extends Activity {
    public static final String PREFS = "royalevision_my_deck";
    private static final String KEY_IDS = "deck_ids";
    private final ArrayList<String> selected = new ArrayList<>();
    private TextView countView;
    private LinearLayout selectedRow;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12,9,18));
        getWindow().setNavigationBarColor(Color.rgb(12,9,18));
        selected.addAll(loadDeck(this));
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12,9,18));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14),dp(16),dp(14),dp(20));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));

        TextView title = text("CALIBRATE MY DECK",22,Color.WHITE,true);
        title.setGravity(Gravity.CENTER);
        page.addView(title,match(dp(5)));
        TextView info = text("Pick the exact 8 cards you are using. During the match this narrows local-play checks, makes hand-change evidence stronger, and gives the sound/visual fusion engine a known set for your side.",12,Color.rgb(205,193,214),false);
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(0,1.16f);
        page.addView(info,match(dp(10)));

        countView = text("",14,Color.rgb(224,188,247),true);
        countView.setGravity(Gravity.CENTER);
        page.addView(countView,match(dp(7)));

        selectedRow = new LinearLayout(this);
        selectedRow.setOrientation(LinearLayout.HORIZONTAL);
        selectedRow.setGravity(Gravity.CENTER);
        page.addView(selectedRow,new LinearLayout.LayoutParams(-1,dp(72)));

        TextView save = button("SAVE 8-CARD DECK",Color.rgb(126,60,165));
        save.setOnClickListener(v -> saveAndClose());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,dp(48));
        sp.topMargin=dp(8);sp.bottomMargin=dp(10);page.addView(save,sp);

        TextView hint = text("Tap a selected card again to remove it.",11,Color.rgb(156,143,166),false);
        hint.setGravity(Gravity.CENTER);page.addView(hint,match(dp(10)));

        for (int i=0;i<CardCatalog.ALL.size();i+=4) {
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(96));rp.bottomMargin=dp(5);page.addView(row,rp);
            for(int j=0;j<4;j++) {
                if(i+j>=CardCatalog.ALL.size()) { row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(92),1f)); continue; }
                CardCatalog.Card c=CardCatalog.ALL.get(i+j);
                if(c.mirror) {
                    // Mirror can still be calibrated; it has no fixed normal Elixir cost.
                }
                FrameLayout cell=cardCell(c);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(92),1f);if(j<3)cp.rightMargin=dp(4);row.addView(cell,cp);
            }
        }
        setContentView(scroll);
        refreshSelected();
    }

    private FrameLayout cardCell(CardCatalog.Card card) {
        FrameLayout f=new FrameLayout(this);
        f.setBackground(panel(Color.rgb(31,25,40),10,Color.rgb(63,52,74)));
        ImageView image=new ImageView(this);
        CardIconLoader.setCard(image,card.id);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-1,dp(65));ip.gravity=Gravity.TOP;f.addView(image,ip);
        TextView name=text(CardCatalog.shortName(card.displayName),8,Color.WHITE,true);
        name.setGravity(Gravity.CENTER);name.setMaxLines(1);
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,dp(27));np.gravity=Gravity.BOTTOM;f.addView(name,np);
        f.setTag(card.id);
        f.setOnClickListener(v -> toggle(card.id));
        return f;
    }

    private void toggle(String id) {
        if(selected.contains(id)) selected.remove(id);
        else {
            if(selected.size()>=8) { Toast.makeText(this,"Your deck already has 8 cards",Toast.LENGTH_SHORT).show(); return; }
            selected.add(id);
        }
        refreshSelected();
    }

    private void refreshSelected() {
        countView.setText(selected.size()+" / 8 selected");
        selectedRow.removeAllViews();
        for(int i=0;i<8;i++) {
            FrameLayout f=new FrameLayout(this);
            f.setBackground(panel(Color.argb(95,37,28,48),9,Color.rgb(83,65,96)));
            if(i<selected.size()) {
                ImageView iv=new ImageView(this);CardIconLoader.setCard(iv,selected.get(i));iv.setScaleType(ImageView.ScaleType.CENTER_CROP);f.addView(iv,new FrameLayout.LayoutParams(-1,-1));
            } else {
                TextView q=text("?",19,Color.rgb(171,156,181),true);q.setGravity(Gravity.CENTER);f.addView(q,new FrameLayout.LayoutParams(-1,-1));
            }
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(66),1f);if(i<7)p.rightMargin=dp(3);selectedRow.addView(f,p);
        }
        countView.setTextColor(selected.size()==8?Color.rgb(171,255,195):Color.rgb(224,188,247));
    }

    private void saveAndClose() {
        if(selected.size()!=8) { Toast.makeText(this,"Select exactly 8 cards first",Toast.LENGTH_LONG).show(); return; }
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_IDS,join(selected)).apply();
        Toast.makeText(this,"Deck calibration saved",Toast.LENGTH_SHORT).show();
        finish();
    }

    public static List<String> loadDeck(Context context) {
        String raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_IDS,"");
        ArrayList<String> out=new ArrayList<>();
        if(raw.length()==0)return out;
        Set<String> seen=new HashSet<>();
        for(String s:raw.split(",")) if(s.length()>0&&!seen.contains(s)){out.add(s);seen.add(s);}
        return out;
    }

    public static boolean costPossible(Context context,int cost) {
        List<String> ids=loadDeck(context);
        if(ids.size()!=8)return true;
        for(String id:ids) {
            for(CardCatalog.Card c:CardCatalog.ALL) {
                if(c.id.equals(id)) {
                    if(c.mirror || c.cost==cost)return true;
                    break;
                }
            }
        }
        return false;
    }

    private static String join(List<String> list){StringBuilder b=new StringBuilder();for(String s:list){if(b.length()>0)b.append(',');b.append(s);}return b.toString();}
    private TextView text(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private TextView button(String s,int c){TextView v=text(s,13,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setBackground(panel(c,13,Color.argb(85,255,255,255)));return v;}
    private GradientDrawable panel(int fill,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(r));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private LinearLayout.LayoutParams match(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=bottom;return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

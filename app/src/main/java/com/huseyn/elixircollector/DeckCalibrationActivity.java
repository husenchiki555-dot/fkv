package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.Context;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deck calibration including current Evo / Hero / Champion special-form slot state. */
public final class DeckCalibrationActivity extends Activity {
    public static final String PREFS = "royalevision_my_deck";
    private static final String KEY_IDS = "deck_ids";

    private final ArrayList<String> selected = new ArrayList<>();
    private final HashMap<String, SpecialFormCalibration.Form> forms = new HashMap<>();
    private TextView countView;
    private TextView formSummary;
    private LinearLayout selectedRow;
    private LinearLayout formsList;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12,9,18));
        getWindow().setNavigationBarColor(Color.rgb(12,9,18));
        selected.addAll(loadDeck(this));
        forms.putAll(SpecialFormCalibration.load(this));
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

        TextView info = text("Pick your exact 8 base cards first. Then set each eligible card to NORMAL, EVO, HERO, or CHAMPION exactly as it is slotted in your real deck. Evo/Hero forms are NOT counted as extra deck cards.",12,Color.rgb(205,193,214),false);
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

        TextView formsTitle = text("SPECIAL FORMS",14,Color.rgb(229,195,248),true);
        formsTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ftp = match(dp(4)); ftp.topMargin = dp(10); page.addView(formsTitle,ftp);

        formSummary = text("",11,Color.rgb(186,170,197),true);
        formSummary.setGravity(Gravity.CENTER);
        page.addView(formSummary,match(dp(6)));

        formsList = new LinearLayout(this);
        formsList.setOrientation(LinearLayout.VERTICAL);
        page.addView(formsList,match(dp(8)));

        TextView specialHint = text("Tap a form button to cycle only through forms that card actually supports. Current standard rules allow 1 Evo slot, 1 Hero/Champion slot, and 1 Wild slot.",10,Color.rgb(166,151,177),false);
        specialHint.setGravity(Gravity.CENTER);
        specialHint.setLineSpacing(0,1.12f);
        page.addView(specialHint,match(dp(9)));

        TextView save = button("SAVE DECK + SPECIAL FORMS",Color.rgb(126,60,165));
        save.setOnClickListener(v -> saveAndClose());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,dp(48));
        sp.bottomMargin=dp(10);page.addView(save,sp);

        TextView hint = text("Tap a card below to add/remove it. The special-form selector appears above once that card is selected.",11,Color.rgb(156,143,166),false);
        hint.setGravity(Gravity.CENTER);page.addView(hint,match(dp(10)));

        for (int i=0;i<CardCatalog.ALL.size();i+=4) {
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(96));rp.bottomMargin=dp(5);page.addView(row,rp);
            for(int j=0;j<4;j++) {
                if(i+j>=CardCatalog.ALL.size()) { row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(92),1f)); continue; }
                CardCatalog.Card c=CardCatalog.ALL.get(i+j);
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

        if (SpecialFormCalibration.hasEvo(card.id) || SpecialFormCalibration.hasHero(card.id) || SpecialFormCalibration.isChampion(card.id)) {
            String badge = SpecialFormCalibration.isChampion(card.id) ? "C" :
                    (SpecialFormCalibration.hasEvo(card.id) && SpecialFormCalibration.hasHero(card.id) ? "E/H" :
                            (SpecialFormCalibration.hasEvo(card.id) ? "E" : "H"));
            TextView b=text(badge,7,Color.WHITE,true);b.setGravity(Gravity.CENTER);
            b.setBackground(panel(Color.argb(205,105,55,146),7,Color.TRANSPARENT));
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(25),dp(17));bp.gravity=Gravity.TOP|Gravity.RIGHT;bp.topMargin=dp(2);bp.rightMargin=dp(2);f.addView(b,bp);
        }

        f.setTag(card.id);
        f.setOnClickListener(v -> toggle(card.id));
        return f;
    }

    private void toggle(String id) {
        if(selected.contains(id)) {
            selected.remove(id);
            forms.remove(baseId(id));
        } else {
            if(selected.size()>=8) { Toast.makeText(this,"Your deck already has 8 cards",Toast.LENGTH_SHORT).show(); return; }
            CardCatalog.Card added = CardCatalog.find(id);
            for (String existingId : selected) {
                CardCatalog.Card existing = CardCatalog.find(existingId);
                if (added != null && existing != null && added.deckId.equals(existing.deckId)) {
                    Toast.makeText(this, "That is another form of a card already selected",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            selected.add(id);
            forms.putIfAbsent(baseId(id), SpecialFormCalibration.Form.NORMAL);
        }
        refreshSelected();
    }

    private void cycleForm(String id) {
        String base=baseId(id);
        List<SpecialFormCalibration.Form> allowed=SpecialFormCalibration.allowedForms(base);
        SpecialFormCalibration.Form current=forms.getOrDefault(base,SpecialFormCalibration.Form.NORMAL);
        int i=allowed.indexOf(current); if(i<0)i=0;
        SpecialFormCalibration.Form next=allowed.get((i+1)%allowed.size());
        forms.put(base,next);
        String problem=SpecialFormCalibration.validate(forms);
        if(problem!=null) {
            forms.put(base,current);
            Toast.makeText(this,problem,Toast.LENGTH_LONG).show();
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
                String id=selected.get(i);
                SpecialFormCalibration.Form form=forms.getOrDefault(baseId(id),SpecialFormCalibration.Form.NORMAL);
                ImageView iv=new ImageView(this);CardIconLoader.setCardForm(iv,id,form);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);f.addView(iv,new FrameLayout.LayoutParams(-1,-1));
                if(form!=SpecialFormCalibration.Form.NORMAL) {
                    TextView badge=text(shortForm(form),8,Color.WHITE,true);badge.setGravity(Gravity.CENTER);
                    badge.setBackground(panel(Color.argb(220,103,48,143),6,Color.TRANSPARENT));
                    FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(30),dp(17));bp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;bp.bottomMargin=dp(2);f.addView(badge,bp);
                }
            } else {
                TextView q=text("?",19,Color.rgb(171,156,181),true);q.setGravity(Gravity.CENTER);f.addView(q,new FrameLayout.LayoutParams(-1,-1));
            }
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(66),1f);if(i<7)p.rightMargin=dp(3);selectedRow.addView(f,p);
        }
        countView.setTextColor(selected.size()==8?Color.rgb(171,255,195):Color.rgb(224,188,247));

        formsList.removeAllViews();
        for(String id:selected) {
            List<SpecialFormCalibration.Form> allowed=SpecialFormCalibration.allowedForms(baseId(id));
            if(allowed.size()<=1) continue;
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6),dp(4),dp(6),dp(4));
            row.setBackground(panel(Color.argb(100,34,27,44),9,Color.rgb(63,52,74)));

            ImageView iv=new ImageView(this);
            SpecialFormCalibration.Form form=forms.getOrDefault(baseId(id),SpecialFormCalibration.Form.NORMAL);
            CardIconLoader.setCardForm(iv,id,form);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(iv,new LinearLayout.LayoutParams(dp(42),dp(48)));

            TextView name=text(displayFor(id),11,Color.WHITE,true);name.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(48),1f);np.leftMargin=dp(8);row.addView(name,np);

            TextView formButton=button(form.name(),formColor(form));
            formButton.setTextSize(10);formButton.setOnClickListener(v -> cycleForm(id));
            row.addView(formButton,new LinearLayout.LayoutParams(dp(86),dp(38)));

            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(56));rp.bottomMargin=dp(4);formsList.addView(row,rp);
        }
        formSummary.setText(SpecialFormCalibration.summary(forms));
        String issue=SpecialFormCalibration.validate(forms);
        formSummary.setTextColor(issue==null?Color.rgb(179,225,193):Color.rgb(255,164,176));
    }

    private void saveAndClose() {
        if(selected.size()!=8) { Toast.makeText(this,"Select exactly 8 cards first",Toast.LENGTH_LONG).show(); return; }
        String issue=SpecialFormCalibration.validate(forms);
        if(issue!=null) { Toast.makeText(this,issue,Toast.LENGTH_LONG).show(); return; }

        // Save only forms for cards that are actually in this deck.
        HashMap<String,SpecialFormCalibration.Form> active=new HashMap<>();
        for(String id:selected) active.put(baseId(id),forms.getOrDefault(baseId(id),SpecialFormCalibration.Form.NORMAL));
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_IDS,join(selected)).apply();
        SpecialFormCalibration.save(this,active);
        Toast.makeText(this,"Deck + Evo/Hero/Champion calibration saved",Toast.LENGTH_SHORT).show();
        finish();
    }

    public static List<String> loadDeck(Context context) {
        String raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_IDS,"");
        ArrayList<String> out=new ArrayList<>();
        if(raw==null||raw.length()==0)return out;
        Set<String> seen=new HashSet<>();
        for(String s:raw.split(",")) {
            CardCatalog.Card card=CardCatalog.find(s);
            if(card!=null&&!seen.contains(card.deckId)&&out.size()<8){out.add(card.id);seen.add(card.deckId);}
        }
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

    private String displayFor(String id){for(CardCatalog.Card c:CardCatalog.ALL)if(c.id.equals(id))return c.displayName;return id.replace('_',' ');}
    private static String baseId(String id){if("spirit_empress_ground".equals(id)||"spirit_empress_flying".equals(id))return "spirit_empress";return id;}
    private static String shortForm(SpecialFormCalibration.Form f){if(f==SpecialFormCalibration.Form.EVO)return "EVO";if(f==SpecialFormCalibration.Form.HERO)return "HERO";if(f==SpecialFormCalibration.Form.CHAMPION)return "CH";return "";}
    private int formColor(SpecialFormCalibration.Form f){if(f==SpecialFormCalibration.Form.EVO)return Color.rgb(103,56,154);if(f==SpecialFormCalibration.Form.HERO)return Color.rgb(46,98,155);if(f==SpecialFormCalibration.Form.CHAMPION)return Color.rgb(148,91,35);return Color.rgb(62,55,72);}
    private static String join(List<String> list){StringBuilder b=new StringBuilder();for(String s:list){if(b.length()>0)b.append(',');b.append(s);}return b.toString();}
    private TextView text(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private TextView button(String s,int c){TextView v=text(s,13,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setBackground(panel(c,13,Color.argb(85,255,255,255)));return v;}
    private GradientDrawable panel(int fill,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(r));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private LinearLayout.LayoutParams match(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=bottom;return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

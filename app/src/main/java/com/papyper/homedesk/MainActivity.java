package com.papyper.homedesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.SplashScreen;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/** Home screen: the list of saved apps (web pages). Tap opens one full screen; ⋮ edits it. */
public class MainActivity extends Activity {

    private static final int MENU_ADD = 1, MENU_USAGE = 2;
    /** Set by "All apps" on a page: show the list instead of jumping to the start app. */
    static final String EXTRA_SHOW_LIST = "show_list";

    private SiteStore store;
    private UsageStore usage;
    private List<SiteStore.Site> sites;
    private final SiteAdapter adapter = new SiteAdapter();
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SiteStore(this);
        usage = new UsageStore(this);

        // Fresh start from the app icon. savedInstanceState alone can't tell: Android also restores the
        // list after the app was killed (that should still jump), and a rotation must not jump.
        Intent intent = getIntent();
        boolean fromIcon = Intent.ACTION_MAIN.equals(intent.getAction())
                && !intent.getBooleanExtra(EXTRA_SHOW_LIST, false)
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        boolean jump = fromIcon && (savedInstanceState == null || !listShown);
        listShown = true;
        if (jump && openStartSite())
            return; // don't build the list we're about to leave

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setOnItemClickListener((parent, view, position, id) -> open(sites.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showSiteMenu(view, sites.get(position));
            return true;
        });

        empty = new TextView(this);
        empty.setText("No apps yet.\nTap + to add one.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        list.setEmptyView(empty);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(list);
        root.addView(empty);
        // Android 15 draws apps edge to edge: keep the last row clear of the navigation bar.
        list.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, 0, bars.right, bars.bottom + Icons.dp(this, 8));
            return insets;
        });
        setContentView(root);
        usage.prune();
    }

    private static boolean listShown; // per process: the list was already on screen once

    /** App icon tapped: go straight to the start site (if one is set) and drop the list's task. */
    private boolean openStartSite() {
        SiteStore.Site start = store.find(store.startSiteId());
        if (start == null)
            return false;
        open(start);
        finishAndRemoveTask(); // next icon tap starts here again and brings that site back
        overridePendingTransition(0, 0);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing())
            return;
        reload();
    }

    private void reload() {
        sites = store.load();
        adapter.notifyDataSetChanged();
    }

    private void saveAndRefresh(List<Long> deletedIds) {
        store.save(sites);
        Icons.syncShortcuts(this, sites, deletedIds);
        adapter.notifyDataSetChanged();
    }

    /** Opens a site at once: no slide animation, and a plain black start screen instead of the icon splash. */
    private void open(SiteStore.Site site) {
        ActivityOptions opts = ActivityOptions.makeCustomAnimation(this, 0, 0);
        if (Build.VERSION.SDK_INT >= 33)
            opts.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
        startActivity(Icons.siteIntent(this, site), opts.toBundle());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ADD, 0, "Add app")
                .setIcon(android.R.drawable.ic_input_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_USAGE, 1, "Usage")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD) {
            editSite(null);
            return true;
        }
        if (item.getItemId() == MENU_USAGE) {
            startActivity(new Intent(this, UsageActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSiteMenu(View anchor, SiteStore.Site site) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        Menu m = menu.getMenu();
        m.add(0, 1, 0, "Edit");
        m.add(0, 6, 1, "Usage");
        m.add(0, 2, 1, "Add to home screen");
        m.add(0, 3, 2, "Move up");
        m.add(0, 4, 3, "Move down");
        m.add(0, 5, 4, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            int i = sites.indexOf(site);
            switch (item.getItemId()) {
                case 1: editSite(site); break;
                case 2: Icons.pinShortcut(this, site); break;
                case 3: if (i > 0) { Collections.swap(sites, i, i - 1); saveAndRefresh(null); } break;
                case 4: if (i >= 0 && i < sites.size() - 1) { Collections.swap(sites, i, i + 1); saveAndRefresh(null); } break;
                case 5: confirmDelete(site); break;
                case 6: startActivity(new Intent(this, UsageActivity.class).putExtra(UsageActivity.EXTRA_APP_ID, site.id)); break;
            }
            return true;
        });
        menu.show();
    }

    private void confirmDelete(SiteStore.Site site) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + site.name + "?")
                .setMessage("Removes it from the list, with its usage history. A saved login for it stays in HomeDesk.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    sites.remove(site);
                    usage.forget(site.id);
                    if (store.startSiteId() == site.id)
                        store.setStartSiteId(0);
                    saveAndRefresh(Collections.singletonList(site.id));
                })
                .show();
    }

    /** Add (site == null) or edit a site. */
    private void editSite(SiteStore.Site site) {
        boolean isNew = site == null;
        int pad = Icons.dp(this, 20);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, Icons.dp(this, 8), pad, 0);

        EditText name = new EditText(this);
        name.setHint("Name (e.g. Mail)");
        name.setSingleLine();
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText url = new EditText(this);
        url.setHint("Web address (e.g. mail.example.com)");
        url.setSingleLine();
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        CheckBox fullScreen = new CheckBox(this);
        fullScreen.setText("Full screen (hide status and navigation bars)");
        CheckBox keepOn = new CheckBox(this);
        keepOn.setText("Keep screen on");
        CheckBox overlay = new CheckBox(this);
        overlay.setText("Keyboard covers the page instead of shrinking it (best for remote desktop pages)");
        CheckBox startSite = new CheckBox(this);
        startSite.setText("Open automatically when HomeDesk starts");

        // permissions Chrome would ask for; HomeDesk asks once and remembers (Clipboard/NotificationBridge)
        TextView clipLabel = settingLabel("Clipboard (paste from this phone)");
        android.widget.RadioGroup clip = modeChooser(isNew ? ClipboardBridge.ASK : site.clipboard);
        TextView notifyLabel = settingLabel("Notifications");
        android.widget.RadioGroup notify = modeChooser(isNew ? ClipboardBridge.ASK : site.notifications);

        if (isNew) {
            keepOn.setChecked(true);
        } else {
            name.setText(site.name);
            url.setText(site.url);
            fullScreen.setChecked(site.fullScreen);
            keepOn.setChecked(site.keepScreenOn);
            overlay.setChecked(site.keyboardOverlay);
            startSite.setChecked(store.startSiteId() == site.id);
        }
        form.addView(name);
        form.addView(url);
        form.addView(fullScreen);
        form.addView(keepOn);
        form.addView(overlay);
        form.addView(startSite);
        form.addView(clipLabel);
        form.addView(clip);
        form.addView(notifyLabel);
        form.addView(notify);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); // fits the small cover screen
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Add app" : "Edit app")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = SiteStore.normalizeUrl(url.getText().toString());
            if (u.isEmpty()) {
                url.setError("Enter the web address");
                return;
            }
            String n = name.getText().toString().trim();
            if (n.isEmpty())
                n = android.net.Uri.parse(u).getHost() != null ? android.net.Uri.parse(u).getHost() : u;
            SiteStore.Site s = isNew ? new SiteStore.Site() : site;
            if (isNew)
                s.id = SiteStore.newId();
            s.name = n;
            s.url = u;
            s.fullScreen = fullScreen.isChecked();
            s.keepScreenOn = keepOn.isChecked();
            s.keyboardOverlay = overlay.isChecked();
            s.clipboard = modeOf(clip);
            s.notifications = modeOf(notify);
            if (isNew)
                sites.add(s);
            if (startSite.isChecked())
                store.setStartSiteId(s.id);
            else if (store.startSiteId() == s.id)
                store.setStartSiteId(0);
            saveAndRefresh(null);
            dialog.dismiss();
            if (isNew)
                Toast.makeText(this, "Saved. Tap ⋮ › Add to home screen for a shortcut.", Toast.LENGTH_LONG).show();
        }));
        dialog.show();
    }

    private TextView settingLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setPadding(Icons.dp(this, 4), Icons.dp(this, 12), 0, 0);
        return label;
    }

    /** Ask / Allow / Block radio buttons; the chosen mode is the checked button's tag. */
    private android.widget.RadioGroup modeChooser(String current) {
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        String[] modes = {ClipboardBridge.ASK, ClipboardBridge.ALLOW, ClipboardBridge.BLOCK};
        String[] labels = {"Ask", "Allow", "Block"};
        for (int i = 0; i < modes.length; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTag(modes[i]);
            group.addView(b, new android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            b.setChecked(modes[i].equals(current));
        }
        return group;
    }

    private static String modeOf(android.widget.RadioGroup group) {
        View checked = group.findViewById(group.getCheckedRadioButtonId());
        return checked != null ? (String) checked.getTag() : ClipboardBridge.ASK;
    }

    private final class SiteAdapter extends BaseAdapter {
        @Override public int getCount() { return sites == null ? 0 : sites.size(); }
        @Override public Object getItem(int position) { return sites.get(position); }
        @Override public long getItemId(int position) { return sites.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SiteStore.Site site = sites.get(position);
            Row row = convertView instanceof Row ? (Row) convertView : new Row();
            row.bind(site);
            return row;
        }
    }

    private final class Row extends LinearLayout {
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView title = new TextView(MainActivity.this);
        final TextView subtitle = new TextView(MainActivity.this);
        final ImageButton more = new ImageButton(MainActivity.this);
        SiteStore.Site site;

        Row() {
            super(MainActivity.this);
            int p = Icons.dp(getContext(), 16);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(p, Icons.dp(getContext(), 12), Icons.dp(getContext(), 4), Icons.dp(getContext(), 12));
            setMinimumHeight(Icons.dp(getContext(), 72));

            int size = Icons.dp(getContext(), 44);
            LayoutParams iconLp = new LayoutParams(size, size);
            iconLp.setMarginEnd(p);
            addView(icon, iconLp);

            LinearLayout text = new LinearLayout(getContext());
            text.setOrientation(VERTICAL);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitle.setSingleLine();
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setAlpha(0.65f);
            text.addView(title);
            text.addView(subtitle);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            TypedValue tv = new TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
            more.setImageResource(android.R.drawable.ic_menu_more);
            more.setColorFilter(getContext().getColor(tv.resourceId != 0 ? tv.resourceId : android.R.color.darker_gray));
            more.setContentDescription("Options");
            getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            more.setBackgroundResource(tv.resourceId);
            more.setFocusable(false); // keep the row itself clickable inside the ListView
            more.setOnClickListener(v -> showSiteMenu(more, site));
            int b = Icons.dp(getContext(), 48);
            addView(more, new LayoutParams(b, b));
        }

        void bind(SiteStore.Site s) {
            site = s;
            icon.setImageBitmap(Icons.bitmap(s, Icons.dp(getContext(), 44), true));
            title.setText(s.name);
            // usage instead of the web address
            long last = usage.lastUsed(s.id);
            String text = last == 0 ? "Not used yet"
                    : "Today " + UsageStore.duration(usage.time(s.id, UsageStore.today())) + " · " + UsageStore.ago(last);
            subtitle.setText(store.startSiteId() == s.id ? "Opens at start · " + text : text);
        }
    }
}

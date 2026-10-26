package com.nexora.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.nexora.R;
import com.nexora.data.Resource;
import com.nexora.domain.EligibilityEngine;
import com.nexora.domain.Models;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

/** Shared UI building blocks: generic adapter, row binding, badges, state view, chips, dialogs, formatting. */
public final class UiKit {
    private UiKit() {}

    // ---------------------------------------------------------------- tones & badges

    public enum Tone {
        SUCCESS(R.color.nx_success, R.color.nx_success_container),
        WARNING(R.color.nx_warning, R.color.nx_warning_container),
        DANGER(R.color.nx_danger, R.color.nx_danger_container),
        NEUTRAL(R.color.nx_neutral, R.color.nx_neutral_container),
        PRIMARY(R.color.nx_on_primary_container, R.color.nx_primary_container);

        @ColorRes public final int fg, bg;
        Tone(int fg, int bg) { this.fg = fg; this.bg = bg; }
    }

    public static int color(Context c, @ColorRes int res) { return ContextCompat.getColor(c, res); }

    /** Pill badge; hidden when text is null. */
    public static void badge(TextView v, String text, Tone tone) {
        v.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text == null) return;
        v.setText(text);
        v.setTextColor(color(v.getContext(), tone.fg));
        v.setBackgroundTintList(ColorStateList.valueOf(color(v.getContext(), tone.bg)));
    }

    public static void tint(View circle, Tone tone) {
        circle.setBackgroundTintList(ColorStateList.valueOf(color(circle.getContext(), tone.bg)));
        if (circle instanceof TextView) ((TextView) circle).setTextColor(color(circle.getContext(), tone.fg));
        if (circle instanceof ImageView) ((ImageView) circle).setImageTintList(ColorStateList.valueOf(color(circle.getContext(), tone.fg)));
    }

    public static Tone tone(EligibilityEngine.Status s) {
        switch (s) {
            case ELIGIBLE: return Tone.SUCCESS;
            case NOT_ELIGIBLE: return Tone.DANGER;
            default: return Tone.WARNING;
        }
    }

    public static String label(EligibilityEngine.Status s) {
        switch (s) {
            case ELIGIBLE: return "Eligible";
            case NOT_ELIGIBLE: return "Not eligible";
            default: return "Needs verification";
        }
    }

    public static Tone tone(Models.AppStatus s) {
        switch (s) {
            case SELECTED: return Tone.SUCCESS;
            case REJECTED: return Tone.DANGER;
            case IN_PROGRESS: return Tone.PRIMARY;
            default: return Tone.NEUTRAL;
        }
    }

    public static String label(Models.AppStatus s) {
        switch (s) {
            case SELECTED: return "Selected";
            case REJECTED: return "Not selected";
            case IN_PROGRESS: return "In progress";
            default: return "Submitted";
        }
    }

    public static Tone driveTone(String status) {
        if (Models.DriveStatus.OPEN.name().equals(status)) return Tone.SUCCESS;
        if (Models.DriveStatus.DRAFT.name().equals(status)) return Tone.WARNING;
        return Tone.NEUTRAL;
    }

    public static String title(String enumName) {
        if (enumName == null) return "";
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- generic adapter

    public interface Binder<T> { void bind(View view, T item, int position); }

    /** One adapter for every list: a layout plus a bind function. */
    public static final class Adapter<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final int layout;
        private final Binder<T> binder;
        private final List<T> items = new ArrayList<>();

        public Adapter(@LayoutRes int layout, Binder<T> binder) {
            this.layout = layout;
            this.binder = binder;
        }

        public void submit(List<T> data) {
            items.clear();
            if (data != null) items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false)) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            binder.bind(holder.itemView, items.get(position), position);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    // ---------------------------------------------------------------- rows

    public static View inflate(ViewGroup parent, @LayoutRes int layout) {
        return LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
    }

    /** Binds item_row.xml. Null subtitle/meta/badge are hidden. */
    public static void row(View v, String avatar, Tone avatarTone, String title, String subtitle, String meta,
                           String badge, Tone badgeTone) {
        TextView a = v.findViewById(R.id.avatar);
        a.setText(avatar);
        tint(a, avatarTone);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        setOrHide(v.findViewById(R.id.subtitle), subtitle);
        setOrHide(v.findViewById(R.id.meta), meta);
        badge(v.findViewById(R.id.badge), badge, badgeTone);
        v.findViewById(R.id.row_action).setVisibility(View.GONE);
        v.setOnClickListener(null);
        v.setContentDescription(join(". ", title, subtitle, meta, badge));
    }

    public static void rowAction(View v, @DrawableRes int icon, String description, View.OnClickListener l) {
        ImageButton b = v.findViewById(R.id.row_action);
        b.setVisibility(View.VISIBLE);
        b.setImageResource(icon);
        b.setContentDescription(description);
        b.setOnClickListener(l);
    }

    public static void setOrHide(TextView v, CharSequence text) {
        v.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        v.setText(text);
    }

    public static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    /** Binds item_criterion.xml. */
    public static View criterion(ViewGroup parent, @DrawableRes int icon, Tone tone, String title, String value, String message) {
        View v = inflate(parent, R.layout.item_criterion);
        ImageView i = v.findViewById(R.id.crit_icon);
        i.setImageResource(icon);
        tint(i, tone);
        setOrHide(v.findViewById(R.id.crit_title), title);
        setOrHide(v.findViewById(R.id.crit_value), value);
        setOrHide(v.findViewById(R.id.crit_message), message);
        v.setContentDescription(join(". ", title, value, message));
        parent.addView(v);
        return v;
    }

    /** Binds item_progress_row.xml. */
    public static void progress(ViewGroup parent, String label, int value, String valueText) {
        View v = inflate(parent, R.layout.item_progress_row);
        ((TextView) v.findViewById(R.id.prog_label)).setText(label);
        ((TextView) v.findViewById(R.id.prog_value)).setText(valueText);
        com.google.android.material.progressindicator.LinearProgressIndicator bar = v.findViewById(R.id.prog_bar);
        bar.setProgressCompat(Math.max(0, Math.min(100, value)), true);
        v.setContentDescription(label + ": " + valueText);
        parent.addView(v);
    }

    // ---------------------------------------------------------------- state view

    /** Wraps view_state.xml: loading, empty and error-with-retry states. */
    public static final class StateView {
        private final View root, progress, action;
        private final ImageView icon;
        private final TextView title, message;

        public StateView(View root) {
            this.root = root;
            progress = root.findViewById(R.id.state_progress);
            icon = root.findViewById(R.id.state_icon);
            title = root.findViewById(R.id.state_title);
            message = root.findViewById(R.id.state_message);
            action = root.findViewById(R.id.state_action);
        }

        public void loading() {
            root.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            message.setVisibility(View.GONE);
            action.setVisibility(View.GONE);
        }

        public void empty(@DrawableRes int iconRes, String t, String m) { show(iconRes, t, m, null, null); }

        public void error(String m, Runnable retry) {
            show(R.drawable.ic_cloud_off, "Couldn't load", m, "Retry", retry);
        }

        public void show(@DrawableRes int iconRes, String t, String m, String actionText, Runnable onAction) {
            root.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            icon.setVisibility(View.VISIBLE);
            icon.setImageResource(iconRes);
            setOrHide(title, t);
            setOrHide(message, m);
            action.setVisibility(onAction == null ? View.GONE : View.VISIBLE);
            if (action instanceof TextView && actionText != null) ((TextView) action).setText(actionText);
            action.setOnClickListener(onAction == null ? null : v -> onAction.run());
        }

        public void hide() { root.setVisibility(View.GONE); }
    }

    /**
     * Standard list rendering for a Resource: loading → spinner, error without data → error+retry,
     * empty → empty state, otherwise the items.
     */
    public static <T> void render(Resource<?> r, List<T> items, Adapter<T> adapter, StateView state,
                                  SwipeRefreshLayout refresh, @DrawableRes int emptyIcon, String emptyTitle,
                                  String emptyMessage, Runnable retry) {
        if (refresh != null) refresh.setRefreshing(false);
        adapter.submit(items);
        if (r == null || (r.isLoading() && (items == null || items.isEmpty()))) state.loading();
        else if (r.isError() && (items == null || items.isEmpty())) state.error(r.message, retry);
        else if (items == null || items.isEmpty()) state.empty(emptyIcon, emptyTitle, emptyMessage);
        else state.hide();
    }

    // ---------------------------------------------------------------- chips

    public static Chip chip(ChipGroup group, String text, boolean checkable, boolean checked) {
        Chip c = new Chip(group.getContext());
        c.setText(text);
        c.setCheckable(checkable);
        c.setChecked(checked);
        c.setClickable(checkable);
        c.setCheckedIconVisible(checkable);
        group.addView(c);
        return c;
    }

    /** Display-only chips, or removable ones when onRemove is given. */
    public static void chips(ChipGroup group, List<String> items, Consumer<String> onRemove) {
        group.removeAllViews();
        for (String s : items) {
            Chip c = chip(group, s, false, false);
            if (onRemove != null) {
                c.setCloseIconVisible(true);
                c.setCloseIconContentDescription("Remove " + s);
                c.setOnCloseIconClickListener(v -> onRemove.accept(s));
            }
        }
    }

    public static List<String> checkedTags(ChipGroup g) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            Chip c = (Chip) g.getChildAt(i);
            if (c.isChecked()) out.add((String) c.getTag());
        }
        return out;
    }

    // ---------------------------------------------------------------- inputs

    public static String text(TextInputLayout l) {
        return l.getEditText() == null || l.getEditText().getText() == null ? "" : l.getEditText().getText().toString().trim();
    }

    public static void setText(TextInputLayout l, String s) {
        if (l.getEditText() != null) l.getEditText().setText(s == null ? "" : s);
    }

    /** Shows or clears the error; returns true when valid. */
    public static boolean check(TextInputLayout l, String error) {
        l.setError(error);
        return error == null;
    }

    /**
     * Once a field shows an error, re-run its validator on every edit so the error clears as soon
     * as the input becomes valid (fields without an error are left alone until the next submit).
     */
    public static void revalidate(TextInputLayout l, java.util.function.Function<String, String> validator) {
        if (l.getEditText() == null) return;
        l.getEditText().addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (l.getError() != null) l.setError(validator.apply(s.toString().trim()));
            }
        });
    }

    public static String nullIfBlank(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }

    // ---------------------------------------------------------------- dialogs

    public interface FormSubmit { boolean submit(String[] values, TextInputLayout[] fields); }

    /** Form dialog with up to 4 fields from dialog_form.xml; stays open until submit returns true. */
    public static AlertDialog form(Context ctx, String title, String message, String[] hints, String[] values,
                                   int[] inputTypes, String positive, FormSubmit onSubmit) {
        View body = LayoutInflater.from(ctx).inflate(R.layout.dialog_form, null);
        int[] ids = {R.id.f1, R.id.f2, R.id.f3, R.id.f4};
        TextInputLayout[] fields = new TextInputLayout[hints.length];
        for (int i = 0; i < hints.length; i++) {
            fields[i] = body.findViewById(ids[i]);
            fields[i].setVisibility(View.VISIBLE);
            fields[i].setHint(hints[i]);
            if (inputTypes != null && fields[i].getEditText() != null) fields[i].getEditText().setInputType(inputTypes[i]);
            if (values != null && values[i] != null) setText(fields[i], values[i]);
        }
        setOrHide(body.findViewById(R.id.form_message), message);
        AlertDialog d = new MaterialAlertDialogBuilder(ctx).setTitle(title).setView(body)
                .setPositiveButton(positive, null).setNegativeButton(android.R.string.cancel, null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String[] out = new String[fields.length];
            for (int i = 0; i < fields.length; i++) out[i] = text(fields[i]);
            if (onSubmit.submit(out, fields)) d.dismiss();
        }));
        d.show();
        return d;
    }

    public static void confirm(Context ctx, String title, String message, String positive, Runnable onYes) {
        new MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message)
                .setPositiveButton(positive, (d, w) -> onYes.run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void snack(View anchor, String message) {
        if (anchor != null && message != null) Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show();
    }

    /** Date picker (and optional time picker). futureOnly blocks past dates; pastOnly blocks future dates. */
    public static void pickDate(FragmentManager fm, Date initial, boolean withTime, boolean futureOnly, boolean pastOnly,
                                Consumer<Date> onPicked) {
        CalendarConstraints.Builder cons = new CalendarConstraints.Builder();
        if (futureOnly) cons.setValidator(DateValidatorPointForward.now());
        if (pastOnly) cons.setValidator(com.google.android.material.datepicker.DateValidatorPointBackward.now());
        long start = initial == null ? MaterialDatePicker.todayInUtcMilliseconds() : toUtcDay(initial);
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(start).setCalendarConstraints(cons.build()).build();
        picker.addOnPositiveButtonClickListener(utcDay -> {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.setTimeInMillis(utcDay);
            Calendar local = Calendar.getInstance();
            local.clear();
            local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59);
            if (!withTime) {
                if (pastOnly) local.set(Calendar.HOUR_OF_DAY, 12);
                if (pastOnly) local.set(Calendar.MINUTE, 0);
                if (pastOnly && local.getTimeInMillis() > System.currentTimeMillis()) local.setTimeInMillis(System.currentTimeMillis());
                onPicked.accept(local.getTime());
                return;
            }
            Calendar init = Calendar.getInstance();
            if (initial != null) init.setTime(initial);
            MaterialTimePicker tp = new MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(initial == null ? 17 : init.get(Calendar.HOUR_OF_DAY))
                    .setMinute(initial == null ? 0 : init.get(Calendar.MINUTE)).build();
            tp.addOnPositiveButtonClickListener(v -> {
                local.set(Calendar.HOUR_OF_DAY, tp.getHour());
                local.set(Calendar.MINUTE, tp.getMinute());
                onPicked.accept(local.getTime());
            });
            tp.show(fm, "time");
        });
        picker.show(fm, "date");
    }

    private static long toUtcDay(Date d) {
        Calendar local = Calendar.getInstance();
        local.setTime(d);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    public static void openUrl(Context ctx, String url) {
        if (url == null) return;
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(ctx, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- fragment bases

    /** Base screen: sets the toolbar title and exposes the activity-scoped ViewModels. */
    public abstract static class Screen extends Fragment {
        protected abstract String title();

        @Override public void onResume() {
            super.onResume();
            requireActivity().setTitle(title());
        }

        protected StudentViewModel student() { return new ViewModelProvider(requireActivity()).get(StudentViewModel.class); }

        protected OfficerViewModel officer() { return new ViewModelProvider(requireActivity()).get(OfficerViewModel.class); }

        protected MainActivity main() { return MainActivity.of(this); }

        protected String arg(String key) { return getArguments() == null ? null : getArguments().getString(key); }

        /** Writes need the server (rules are checked there), so block them with a clear message when offline. */
        protected boolean requireOnline() {
            if (main().isOnline()) return true;
            snack(getView(), "You're offline. Connect to the internet to do this.");
            return false;
        }
    }

    /** Base for every list screen built on fragment_list.xml. */
    public abstract static class ListScreen extends Screen {
        protected TextInputLayout searchLayout;
        protected View chipRow;
        protected ChipGroup chips;
        protected ImageButton action;
        protected TextView header;
        protected RecyclerView list;
        protected SwipeRefreshLayout refresh;
        protected StateView state;
        protected com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab;

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            View v = inflater.inflate(R.layout.fragment_list, container, false);
            searchLayout = v.findViewById(R.id.search_layout);
            chipRow = v.findViewById(R.id.chip_row);
            chips = v.findViewById(R.id.chips);
            action = v.findViewById(R.id.action);
            header = v.findViewById(R.id.header_text);
            list = v.findViewById(R.id.list);
            refresh = v.findViewById(R.id.refresh);
            state = new StateView(v.findViewById(R.id.state));
            fab = v.findViewById(R.id.fab);
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
            // Data is live (snapshot listeners), so pull-to-refresh only re-renders.
            refresh.setOnRefreshListener(this::render);
            return v;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            super.onViewCreated(view, saved);
            setup();
        }

        protected abstract void setup();

        protected abstract void render();

        /** Enables the search box; render() runs on every change. */
        protected void enableSearch(String hint) {
            searchLayout.setVisibility(View.VISIBLE);
            searchLayout.setHint(hint);
            searchLayout.getEditText().addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) { render(); }
            });
        }

        protected String query() { return text(searchLayout).toLowerCase(Locale.ROOT); }

        /** Single-select chip tabs; returns nothing, use {@link #selectedTab()}. */
        protected void tabs(String... names) {
            chipRow.setVisibility(View.VISIBLE);
            chips.setSingleSelection(true);
            chips.setSelectionRequired(true);
            for (int i = 0; i < names.length; i++) {
                Chip c = chip(chips, names[i], true, i == 0);
                c.setTag(names[i]);
            }
            chips.setOnCheckedStateChangeListener((g, ids) -> render());
        }

        protected String selectedTab() {
            List<String> t = checkedTags(chips);
            return t.isEmpty() ? null : t.get(0);
        }
    }

    // ---------------------------------------------------------------- formatting

    public static String date(Date d) {
        return d == null ? "—" : new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d);
    }

    public static String dateTime(Date d) {
        return d == null ? "—" : new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(d);
    }

    public static String relative(Date d) {
        if (d == null) return "";
        // Server timestamps can be estimated slightly ahead of the device clock.
        if (Math.abs(System.currentTimeMillis() - d.getTime()) < DateUtils.MINUTE_IN_MILLIS) return "just now";
        return DateUtils.getRelativeTimeSpanString(d.getTime(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /** "Closes in 2 days" / "Closes today" / "Closed 3 days ago". */
    public static String deadline(Date d) {
        if (d == null) return "No deadline set";
        long diff = d.getTime() - System.currentTimeMillis();
        if (diff <= 0) return "Applications closed " + relative(d);
        long hours = diff / DateUtils.HOUR_IN_MILLIS;
        if (hours < 24) return "Closes in " + Math.max(1, hours) + (hours == 1 ? " hour" : " hours");
        long days = hours / 24;
        return "Closes in " + days + (days == 1 ? " day" : " days") + " · " + date(d);
    }

    public static String lpa(double v) {
        return v == Math.floor(v) ? String.format(Locale.US, "₹%.0f LPA", v) : String.format(Locale.US, "₹%.1f LPA", v);
    }

    public static int dp(Context c, int dp) { return Math.round(dp * c.getResources().getDisplayMetrics().density); }
}

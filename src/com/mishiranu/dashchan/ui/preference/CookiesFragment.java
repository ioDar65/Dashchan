package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.DialogMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class CookiesFragment extends PreferenceFragment {
	private static final String EXTRA_CHAN_NAME = "chanName";

	private ChanConfiguration configuration;
	private final HashMap<String, ChanConfiguration.CookieData> cookies = new HashMap<>();

	public CookiesFragment() {}

	public CookiesFragment(String chanName) {
		Bundle args = new Bundle();
		args.putCharSequence(EXTRA_CHAN_NAME, chanName);
		setArguments(args);
	}

	private String getChanName() {
		return requireArguments().getString(EXTRA_CHAN_NAME);
	}

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		String chanName = getChanName();

		configuration = ChanConfiguration.get(chanName);
		ArrayList<ChanConfiguration.CookieData> cookies = configuration.getCookies();
		Collections.sort(cookies, (l, r) -> StringUtils.compare(l.cookie, r.cookie, true));
		for (ChanConfiguration.CookieData cookieData : cookies) {
			this.cookies.put(cookieData.cookie, cookieData);
			CookiePreference preference = new CookiePreference(requireContext(),
					cookieData.cookie, cookieData.displayName);
			preference.setValue(cookieData.value);
			preference.setViewGrayed(cookieData.blocked);
			preference.setOnClickListener(onClick);
			addPreference(preference, false);
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.manage_cookies), null);
		if (!ChanManager.getInstance().isExistingChanName(getChanName())) {
			((FragmentHandler) requireActivity()).removeFragment();
		}
	}

	private static class CookiePreference extends Preference.Runtime<String> {
		public CookiePreference(Context context, String key, CharSequence title) {
			super(context, key, null, title, Preference::getValue);
		}

		private boolean viewGrayed = false;

		public void setViewGrayed(boolean viewGrayed) {
			if (this.viewGrayed != viewGrayed) {
				this.viewGrayed = viewGrayed;
				// Invalidate view
				setValue(getValue());
			}
		}

		@Override
		public void bindViewHolder(ViewHolder viewHolder) {
			super.bindViewHolder(viewHolder);
			viewHolder.title.setEnabled(!viewGrayed);
			viewHolder.summary.setEnabled(!viewGrayed);
		}
	}

	private final Preference.OnClickListener<String> onClick = preference -> {
		String cookie = preference.key;
		ChanConfiguration.CookieData cookieData = cookies.get(cookie);
		if (cookieData != null) {
			ActionDialog dialog = new ActionDialog(cookie, cookieData.blocked);
			dialog.show(getChildFragmentManager(), ActionDialog.TAG);
		}
	};

	private void removeCookie(String cookie, boolean preferenceOnly) {
		if (!preferenceOnly) {
			configuration.storeCookie(cookie, null, null);
			configuration.commit();
		}
		CookiePreference preference = (CookiePreference) findPreference(cookie);
		if (preference != null) {
			int size = removePreference(preference);
			cookies.remove(cookie);
			if (size == 0) {
				((FragmentHandler) requireActivity()).removeFragment();
			}
		}
	}

	private void setBlocked(String cookie, boolean blocked) {
		boolean remove = configuration.setCookieBlocked(cookie, blocked);
		configuration.commit();
		if (remove) {
			removeCookie(cookie, true);
		} else {
			ChanConfiguration.CookieData cookieData = cookies.get(cookie);
			if (cookieData != null) {
				cookies.put(cookie, new ChanConfiguration.CookieData(cookie,
						cookieData.value, cookieData.displayName, blocked));
			}
		}
		CookiePreference preference = (CookiePreference) findPreference(cookie);
		if (preference != null) {
			preference.setViewGrayed(blocked);
		}
	}

	public static class ActionDialog extends DialogFragment {
		private static final String TAG = ActionDialog.class.getName();

		private static final String EXTRA_COOKIE = "cookie";
		private static final String EXTRA_BLOCKED = "blocked";

		public ActionDialog() {}

		public ActionDialog(String cookie, boolean blocked) {
			Bundle args = new Bundle();
			args.putString(EXTRA_COOKIE, cookie);
			args.putBoolean(EXTRA_BLOCKED, blocked);
			setArguments(args);
		}

		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			Bundle args = requireArguments();
			boolean blocked = args.getBoolean(EXTRA_BLOCKED);
			DialogMenu dialogMenu = new DialogMenu(requireContext());
			dialogMenu.add(R.string.block, blocked, () -> ((CookiesFragment) getParentFragment())
					.setBlocked(args.getString(EXTRA_COOKIE), !args.getBoolean(EXTRA_BLOCKED)));
			if (!blocked) {
				dialogMenu.add(R.string.delete, () -> ((CookiesFragment) getParentFragment())
						.removeCookie(args.getString(EXTRA_COOKIE), false));
			}
			return dialogMenu.create();
		}
	}
}

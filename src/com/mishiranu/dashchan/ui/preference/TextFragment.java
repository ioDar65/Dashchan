package com.mishiranu.dashchan.ui.preference;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.text.HtmlParser;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.CommentTextView;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class TextFragment extends Fragment implements View.OnClickListener {
	private static final String EXTRA_TYPE = "type";
	private static final String EXTRA_CONTENT = "content";

	public enum Type {LICENSES, CHANGELOG}

	private CommentTextView textView;

	public TextFragment() {}

	public TextFragment(Type type, String content) {
		Bundle args = new Bundle();
		args.putString(EXTRA_TYPE, type.name());
		args.putString(EXTRA_CONTENT, content);
		setArguments(args);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		Bundle args = requireArguments();
		Type type = Type.valueOf(args.getString(EXTRA_TYPE));
		String content = args.getString(EXTRA_CONTENT);
		switch (type) {
			case LICENSES: {
				content = IOUtils.readRawResourceString(getResources(), R.raw.licenses);
				break;
			}
		}
		CharSequence text = HtmlParser.spanify(content, new Markup(), null, null);
		ThemeEngine.getColorScheme(requireContext()).apply(text);
		float density = ResourceUtils.obtainDensity(this);
		textView = new CommentTextView(requireActivity(), null, android.R.attr.textAppearanceLarge);
		int padding = (int) (16f * density);
		textView.setPadding(padding, padding, padding, padding);
		ViewUtils.setTextSizeScaled(textView, 14);
		textView.setText(text);
		ScrollView scrollView = new ScrollView(requireActivity()) {
			@Override
			public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
				// Don't scroll on select
				return true;
			}
		};
		scrollView.setId(android.R.id.list);
		ThemeEngine.applyStyle(scrollView);
		FrameLayout frameLayout = new FrameLayout(requireActivity());
		frameLayout.setOnClickListener(this);
		scrollView.addView(frameLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		frameLayout.addView(textView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		return scrollView;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		if (!C.API_MARSHMALLOW) {
			((View) getView().getParent()).setPadding(0, 0, 0, 0);
		}
		switch (Type.valueOf(requireArguments().getString(EXTRA_TYPE))) {
			case LICENSES: {
				((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.foss_licenses), null);
				break;
			}
			case CHANGELOG: {
				((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.changelog), null);
				break;
			}
		}
	}

	private long lastClickTime;

	@Override
	public void onClick(View v) {
		long time = SystemClock.elapsedRealtime();
		if (time - lastClickTime < ViewConfiguration.getDoubleTapTimeout()) {
			lastClickTime = 0L;
			textView.startSelection();
		} else {
			lastClickTime = time;
		}
	}

	private static class Markup extends ChanMarkup {
		public Markup() {
			super(false);
			addTag("h1", TAG_HEADING);
			addTag("h2", TAG_HEADING);
			addTag("h3", TAG_HEADING);
			addTag("h4", TAG_HEADING);
			addTag("h5", TAG_HEADING);
			addTag("h6", TAG_HEADING);
			addTag("strong", TAG_BOLD);
			addTag("em", TAG_ITALIC);
			addTag("pre", TAG_CODE);
		}
	}
}

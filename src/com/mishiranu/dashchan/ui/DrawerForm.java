package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.ChanConfiguration;
import chan.content.ChanLocator;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.service.WatcherService;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.graphics.ChanIconDrawable;
import com.mishiranu.dashchan.util.ConfigurationLock;
import com.mishiranu.dashchan.util.DialogMenu;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ToastUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.EdgeEffectHandler;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.SortableHelper;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.WatcherView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrawerForm extends RecyclerView.Adapter<DrawerForm.ViewHolder> implements EdgeEffectHandler.Shift,
		DrawerLayout.DrawerListener, EditText.OnEditorActionListener, SortableHelper.Callback<DrawerForm.ViewHolder>,
		WatcherService.Client.Callback {
	private final Context context;
	private final Callback callback;
	private final ConfigurationLock configurationLock;
	private final WatcherView.ColorSet watcherViewColorSet;
	private final SortableHelper<ViewHolder> sortableHelper;
	private final int drawerIconColor;

	private final WatcherService.Client watcherServiceClient;
	private final InputMethodManager inputMethodManager;

	private final PaddedRecyclerView recyclerView;
	private final EditText searchEdit;
	private final View selectorContainer;
	private final View headerView;
	private final View restartView;
	private final TextView chanNameView;
	private final ImageView chanSelectorIcon;

	private final HashMap<String, ChanIconDrawable> chanIcons = new HashMap<>();
	private final HashSet<String> watcherSupportSet = new HashSet<>();

	private final ArrayList<ListItem> chans = new ArrayList<>();
	private final ArrayList<ListItem> pages = new ArrayList<>();
	private final ArrayList<ListItem> favorites = new ArrayList<>();
	private final ArrayList<ListItem> menu = new ArrayList<>();

	private boolean mergeChans = false;
	private boolean showHistory = false;
	private int pagesListMode = -1;
	private boolean chanSelectMode = false;
	private boolean showRestartButton = false;
	private CategoriesOrder categoriesOrder;
	private String chanName;

	public static final int RESULT_REMOVE_ERROR_MESSAGE = 0x00000001;
	public static final int RESULT_SUCCESS = 0x00000002;

	public static final int MENU_ITEM_BOARDS = 1;
	public static final int MENU_ITEM_USER_BOARDS = 2;
	public static final int MENU_ITEM_HISTORY = 3;
	public static final int MENU_ITEM_PREFERENCES = 4;

	private enum CategoriesOrder {PAGES_FIRST, FAVORITES_FIRST, HIDE_PAGES}

	public static class Page implements Comparable<Page> {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String threadTitle;
		public final long createRealtime;

		public Page(String chanName, String boardName, String threadNumber,
				String threadTitle, long createRealtime) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.threadTitle = threadTitle;
			this.createRealtime = createRealtime;
		}

		@Override
		public int compareTo(Page page) {
			return Long.compare(page.createRealtime, createRealtime);
		}
	}

	public interface Callback {
		public void onSelectChan(String chanName);
		public void onSelectBoard(String chanName, String boardName, boolean fromCache);
		public boolean onSelectThread(String chanName, String boardName, String threadNumber, String postNumber,
				String threadTitle, boolean fromCache);
		public void onClosePage(String chanName, String boardName, String threadNumber);
		public void onCloseAllPages();
		public int onEnterNumber(int number);
		public void onSelectDrawerMenuItem(int item);
		public void onDraggingStateChanged(boolean dragging);
		public Collection<Page> obtainDrawerPages();
		public void restartApplication();
	}

	public DrawerForm(Context context, Callback callback, ConfigurationLock configurationLock,
			WatcherService.Client watcherServiceClient) {
		this.context = context;
		this.callback = callback;
		this.configurationLock = configurationLock;
		this.watcherServiceClient = watcherServiceClient;

		int enabledColor = ThemeEngine.getTheme(context).accent;
		int disabledColor = 0xff666666;
		int unavailableColor = GraphicsUtils.mixColors(disabledColor, enabledColor & 0x7fffffff);
		watcherViewColorSet = new WatcherView.ColorSet(enabledColor, unavailableColor, disabledColor);

		recyclerView = new PaddedRecyclerView(context);
		recyclerView.setId(R.id.drawer_recycler_view);
		recyclerView.setMotionEventSplittingEnabled(false);
		recyclerView.setClipToPadding(false);
		recyclerView.setEdgeEffectShift(this);
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()) {
			@Override
			public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
					@NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
				if (child == headerView) {
					// Keep EditText on top and don't allow LinearLayoutManager weird scrolls
					int dy = child.getTop() - parent.getPaddingTop();
					if (dy != 0) {
						if (immediate) {
							parent.scrollBy(0, dy);
						} else {
							parent.smoothScrollBy(0, dy);
						}
						return true;
					}
				}
				return false;
			}
		});
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				// Hide keyboard when list is scrolled
				if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
					View focusView = recyclerView.getFocusedChild();
					if (focusView != null) {
						focusView.clearFocus();
						hideKeyboard();
					}
				}
			}
		});
		setHasStableIds(true);
		recyclerView.setAdapter(this);
		DividerItemDecoration dividerItemDecoration = new DividerItemDecoration
				(recyclerView.getContext(), (c, position) -> configureDivider(c, position).translate(false));
		recyclerView.addItemDecoration(dividerItemDecoration);
		dividerItemDecoration.setAboveCallback(position -> {
			ListItem listItem = getItem(position);
			// Attach dividers to the top of sections and menus to fix decorations on dragging
			return listItem.type == ListItem.Type.SECTION || listItem.type == ListItem.Type.MENU;
		});
		recyclerView.setItemAnimator(null);
		sortableHelper = new SortableHelper<>(recyclerView, this);
		drawerIconColor = C.API_LOLLIPOP ? ResourceUtils.getColor(context, android.R.attr.textColorSecondary) : 0;

		float density = ResourceUtils.obtainDensity(context);
		if (!C.API_LOLLIPOP) {
			ViewUtils.setNewPadding(recyclerView, (int) (12f * density), null, (int) (12f * density), null);
		}

		LinearLayout headerView = new LinearLayout(context);
		headerView.setOrientation(LinearLayout.VERTICAL);
		headerView.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT));
		this.headerView = headerView;

		LinearLayout editTextContainer = new LinearLayout(context);
		editTextContainer.setGravity(Gravity.CENTER_VERTICAL);
		// Reset focus to parent view
		editTextContainer.setFocusableInTouchMode(true);
		headerView.addView(editTextContainer);

		searchEdit = new SafePasteEditText(context);
		searchEdit.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
				v.clearFocus();
			}
			return false;
		});
		searchEdit.setHint(context.getString(R.string.code_number_address));
		searchEdit.setOnEditorActionListener(this);
		searchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		searchEdit.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

		ImageView searchIcon = new ImageView(context, null, android.R.attr.buttonBarButtonStyle);
		searchIcon.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonForward, 0));
		if (C.API_LOLLIPOP) {
			searchIcon.setImageTintList(ResourceUtils.getColorStateList(searchIcon.getContext(),
					android.R.attr.textColorPrimary));
		}
		searchIcon.setScaleType(ImageView.ScaleType.CENTER);
		searchIcon.setOnClickListener(v -> onSearchClick());
		editTextContainer.addView(searchEdit, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		editTextContainer.addView(searchIcon, (int) (40f * density), (int) (40f * density));
		if (C.API_LOLLIPOP) {
			editTextContainer.setPadding((int) (12f * density), (int) (8f * density), (int) (8f * density), 0);
		} else {
			editTextContainer.setPadding(0, (int) (2f * density), (int) (4f * density), (int) (2f * density));
		}

		LinearLayout selectorContainer = new LinearLayout(context);
		this.selectorContainer = selectorContainer;
		selectorContainer.setBackgroundResource(ResourceUtils.getResourceId(context,
				android.R.attr.selectableItemBackground, 0));
		selectorContainer.setOrientation(LinearLayout.HORIZONTAL);
		selectorContainer.setGravity(Gravity.CENTER_VERTICAL);
		selectorContainer.setOnClickListener(v -> {
			hideKeyboard();
			setChanSelectMode(!chanSelectMode);
		});
		headerView.addView(selectorContainer);
		selectorContainer.setMinimumHeight((int) (40f * density));
		if (C.API_LOLLIPOP) {
			selectorContainer.setPadding((int) (16f * density), 0, (int) (16f * density), 0);
			((LinearLayout.LayoutParams) selectorContainer.getLayoutParams()).topMargin = (int) (4f * density);
		} else {
			selectorContainer.setPadding((int) (8f * density), 0, (int) (12f * density), 0);
		}

		chanNameView = new TextView(context, null, android.R.attr.textAppearanceListItem);
		ViewUtils.setTextSizeScaled(chanNameView, C.API_LOLLIPOP ? 14 : 16);
		if (C.API_LOLLIPOP) {
			chanNameView.setTypeface(GraphicsUtils.TYPEFACE_MEDIUM);
		} else {
			chanNameView.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
		}
		selectorContainer.addView(chanNameView, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1));

		chanSelectorIcon = new ImageView(context);
		chanSelectorIcon.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonDropDown, 0));
		if (C.API_LOLLIPOP) {
			chanSelectorIcon.setImageTintList(ResourceUtils.getColorStateList(context,
					android.R.attr.textColorPrimary));
		}
		selectorContainer.addView(chanSelectorIcon, (int) (24f * density), (int) (24f * density));
		((LinearLayout.LayoutParams) chanSelectorIcon.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL
				| Gravity.END;

		LinearLayout restartView = new LinearLayout(context);
		restartView.setOrientation(LinearLayout.VERTICAL);
		this.restartView = restartView;

		TextView restartTextView = new TextView(context, null, android.R.attr.textAppearanceSmall);
		restartTextView.setText(R.string.new_extensions_installed__sentence);
		restartTextView.setTextColor(ResourceUtils.getColor(context, android.R.attr.textColorPrimary));
		if (C.API_LOLLIPOP) {
			restartTextView.setPadding((int) (16f * density), (int) (8f * density),
					(int) (16f * density), (int) (8f * density));
		} else {
			restartTextView.setPadding((int) (8f * density), (int) (8f * density),
					(int) (8f * density), (int) (8f * density));
		}
		restartView.addView(restartTextView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		ViewHolder restartButtonViewHolder = createItem(ViewType.ITEM, density);
		restartButtonViewHolder.text.setText(R.string.restart);
		restartButtonViewHolder.itemView.setOnClickListener(v -> callback.restartApplication());
		restartView.addView(restartButtonViewHolder.itemView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		inputMethodManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
		updatePreferencesInternal(true);
		updateChans();
	}

	private void updateConfiguration(String chanName, boolean force) {
		if (!StringUtils.equals(chanName, this.chanName) || force || menu.isEmpty()) {
			this.chanName = chanName;
			ChanConfiguration configuration = ChanConfiguration.get(chanName);
			chanNameView.setText(configuration.getTitle());
			menu.clear();
			Context context = this.context;
			TypedArray typedArray = context.obtainStyledAttributes(new int[] {R.attr.iconDrawerMenuBoards,
					R.attr.iconDrawerMenuUserBoards, R.attr.iconDrawerMenuHistory, R.attr.iconDrawerMenuPreferences});
			boolean hasUserBoards = configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS);
			if (chanName != null && !configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_BOARDS, typedArray.getResourceId(0, 0),
						context.getString(hasUserBoards ? R.string.general_boards : R.string.boards)));
			}
			if (chanName != null && hasUserBoards) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_USER_BOARDS, typedArray.getResourceId(1, 0),
						context.getString(R.string.user_boards)));
			}
			if (chanName != null && Preferences.isRememberHistory()) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_HISTORY, typedArray.getResourceId(2, 0),
						context.getString(R.string.history)));
			}
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_PREFERENCES, typedArray.getResourceId(3, 0),
					context.getString(R.string.preferences)));
			typedArray.recycle();
			invalidateItems(force, true);
		}
	}

	public void updateConfiguration(String chanName) {
		updateConfiguration(chanName, false);
	}

	public View getContentView() {
		return recyclerView;
	}

	public View getHeaderView() {
		return headerView;
	}

	public void setChanSelectMode(boolean enabled) {
		if (chans.size() >= 2 && chanSelectMode != enabled) {
			chanSelectMode = enabled;
			chanSelectorIcon.setRotation(enabled ? 180f : 0f);
			notifyDataSetChanged();
			recyclerView.scrollToPosition(0);
			updateRestartViewVisibility();
		}
	}

	public boolean isChanSelectMode() {
		return chanSelectMode;
	}

	public void updateRestartViewVisibility() {
		boolean showRestartButton = !chanSelectMode && ChanManager.getInstance().hasNewExtensionsInstalled();
		if (this.showRestartButton != showRestartButton) {
			this.showRestartButton = showRestartButton;
			notifyDataSetChanged();
		}
	}

	public void updateChans() {
		ChanManager manager = ChanManager.getInstance();
		Iterable<String> availableChans = manager.getAvailableChanNames();
		int availableChansCount = 0;
		chans.clear();
		watcherSupportSet.clear();
		for (String chanName : availableChans) {
			availableChansCount++;
			ChanConfiguration configuration = ChanConfiguration.get(chanName);
			if (configuration.getOption(ChanConfiguration.OPTION_READ_POSTS_COUNT)) {
				watcherSupportSet.add(chanName);
			}
			chans.add(new ListItem(ListItem.Type.CHAN, 0, chanName, null, null, configuration.getTitle()));
		}
		selectorContainer.setVisibility(availableChansCount >= 2 ? View.VISIBLE : View.GONE);
		if (chanSelectMode && availableChansCount <= 1) {
			setChanSelectMode(false);
		}
		notifyDataSetChanged();
	}

	public void updatePreferences() {
		updatePreferencesInternal(false);
	}

	private void updatePreferencesInternal(boolean initial) {
		boolean mergeChans = Preferences.isMergeChans();
		boolean showHistory = Preferences.isRememberHistory();
		int pagesListMode = Preferences.getPagesListMode();
		if (this.mergeChans != mergeChans || this.showHistory != showHistory ||
				this.pagesListMode != pagesListMode) {
			this.mergeChans = mergeChans;
			this.showHistory = showHistory;
			this.pagesListMode = pagesListMode;
			if (!initial) {
				updateConfiguration(chanName, true);
			}
		}
	}

	@Override
	public int getEdgeEffectShift(EdgeEffectHandler.Side side) {
		int shift = recyclerView.obtainEdgeEffectShift(side);
		return side == EdgeEffectHandler.Side.TOP ? shift + headerView.getPaddingTop() : shift;
	}

	private void onItemClick(int position) {
		ListItem listItem = getItem(position);
		switch (listItem.type) {
			case PAGE:
			case FAVORITE: {
				boolean fromCache = listItem.type == ListItem.Type.PAGE;
				if (!listItem.isThreadItem()) {
					callback.onSelectBoard(listItem.chanName, listItem.boardName, fromCache);
				} else {
					callback.onSelectThread(listItem.chanName, listItem.boardName, listItem.threadNumber, null,
							listItem.title, fromCache);
				}
				break;
			}
			case MENU: {
				callback.onSelectDrawerMenuItem(listItem.data);
				break;
			}
			case CHAN: {
				callback.onSelectChan(listItem.chanName);
				setChanSelectMode(false);
				break;
			}
		}
	}

	private boolean onItemLongClick(ViewHolder holder) {
		if (chanSelectMode) {
			sortableHelper.start(holder);
			return true;
		}
		ListItem listItem = getItem(holder.getAdapterPosition());
		if (listItem.type == ListItem.Type.FAVORITE && listItem.threadNumber != null &&
				FavoritesStorage.getInstance().canSortManually() && holder.isMultipleFingers()) {
			sortableHelper.start(holder);
			return true;
		}
		switch (listItem.type) {
			case PAGE:
			case FAVORITE: {
				DialogMenu dialogMenu = new DialogMenu(context);
				dialogMenu.add(R.string.copy_link, () -> onCopyShareLink(listItem, false));
				if (listItem.isThreadItem()) {
					dialogMenu.add(R.string.share_link, () -> onCopyShareLink(listItem, true));
				}
				if (listItem.type != ListItem.Type.FAVORITE && !FavoritesStorage.getInstance()
						.hasFavorite(listItem.chanName, listItem.boardName, listItem.threadNumber)) {
					dialogMenu.add(R.string.add_to_favorites, () -> {
						if (listItem.isThreadItem()) {
							FavoritesStorage.getInstance().add(listItem.chanName, listItem.boardName,
									listItem.threadNumber, listItem.title, 0);
						} else {
							FavoritesStorage.getInstance().add(listItem.chanName, listItem.boardName);
						}
					});
				}
				if (listItem.type == ListItem.Type.FAVORITE) {
					dialogMenu.add(R.string.remove_from_favorites, () -> FavoritesStorage.getInstance()
							.remove(listItem.chanName, listItem.boardName, listItem.threadNumber));
					if (listItem.threadNumber != null) {
						dialogMenu.add(R.string.rename, () -> {
							EditText editText = new SafePasteEditText(context);
							editText.setSingleLine(true);
							editText.setText(listItem.title);
							editText.setSelection(editText.length());
							LinearLayout linearLayout = new LinearLayout(context);
							linearLayout.setOrientation(LinearLayout.HORIZONTAL);
							linearLayout.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT,
									LinearLayout.LayoutParams.WRAP_CONTENT);
							int padding = context.getResources().getDimensionPixelSize(R.dimen
									.dialog_padding_view);
							linearLayout.setPadding(padding, padding, padding, padding);
							AlertDialog dialog = new AlertDialog.Builder(context)
									.setView(linearLayout).setTitle(R.string.rename)
									.setNegativeButton(android.R.string.cancel, null)
									.setPositiveButton(android.R.string.ok, (d, which) -> {
										String newTitle = editText.getText().toString();
										FavoritesStorage.getInstance().modifyTitle(listItem.chanName,
												listItem.boardName, listItem.threadNumber, newTitle, true);
									}).create();
							dialog.getWindow().setSoftInputMode(WindowManager
									.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
							configurationLock.lockConfiguration(dialog);
							dialog.show();
						});
					}
				}
				dialogMenu.show(configurationLock);
				return true;
			}
		}
		return false;
	}

	private void onCopyShareLink(ListItem listItem, boolean share) {
		ChanLocator locator = ChanLocator.get(listItem.chanName);
		Uri uri = listItem.isThreadItem() ? locator.safe(true).createThreadUri
				(listItem.boardName, listItem.threadNumber)
				: locator.safe(true).createBoardUri(listItem.boardName, 0);
		if (uri != null) {
			if (share) {
				String subject = listItem.title;
				if (StringUtils.isEmptyOrWhitespace(subject)) {
					subject = uri.toString();
				}
				NavigationUtils.shareLink(context, subject, uri);
			} else {
				StringUtils.copyToClipboard(context, uri.toString());
			}
		}
	}

	@Override
	public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {}

	@Override
	public void onDrawerOpened(@NonNull View drawerView) {}

	@Override
	public void onDrawerClosed(@NonNull View drawerView) {
		hideKeyboard();
		setChanSelectMode(false);
	}

	@Override
	public void onDrawerStateChanged(int newState) { }

	private void clearTextAndHideKeyboard() {
		searchEdit.setText(null);
		hideKeyboard();
	}

	private void hideKeyboard() {
		searchEdit.clearFocus();
		if (inputMethodManager != null) {
			inputMethodManager.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
		}
	}

	private static final Pattern PATTERN_NAVIGATION_BOARD_THREAD = Pattern.compile("([\\w_-]+) (\\d+)");
	private static final Pattern PATTERN_NAVIGATION_BOARD = Pattern.compile("/?([\\w_-]+)");
	private static final Pattern PATTERN_NAVIGATION_THREAD = Pattern.compile("#(\\d+)");

	private void onSearchClick() {
		String text = searchEdit.getText().toString().trim();
		int number = -1;
		try {
			number = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			// Not a number, ignore exception
		}
		if (number >= 0) {
			int result = callback.onEnterNumber(number);
			if (FlagUtils.get(result, RESULT_SUCCESS)) {
				clearTextAndHideKeyboard();
				return;
			}
			if (FlagUtils.get(result, RESULT_REMOVE_ERROR_MESSAGE)) {
				return;
			}
		} else {
			{
				String boardName = null;
				String threadNumber = null;
				Matcher matcher = PATTERN_NAVIGATION_BOARD_THREAD.matcher(text);
				if (matcher.matches()) {
					boardName = matcher.group(1);
					threadNumber = matcher.group(2);
				} else {
					matcher = PATTERN_NAVIGATION_BOARD.matcher(text);
					if (matcher.matches()) {
						boardName = matcher.group(1);
					} else {
						matcher = PATTERN_NAVIGATION_THREAD.matcher(text);
						if (matcher.matches()) {
							threadNumber = matcher.group(1);
						}
					}
				}
				if (boardName != null || threadNumber != null) {
					boolean success;
					if (threadNumber == null) {
						callback.onSelectBoard(chanName, boardName, false);
						success = true;
					} else {
						success = callback.onSelectThread(chanName, boardName, threadNumber, null, null, false);
					}
					if (success) {
						clearTextAndHideKeyboard();
						return;
					}
				}
			}
			Uri uri = Uri.parse(text);
			String chanName = ChanManager.getInstance().getChanNameByHost(uri.getAuthority());
			if (chanName != null) {
				boolean success = false;
				String boardName = null;
				String threadNumber = null;
				String postNumber = null;
				ChanLocator locator = ChanLocator.get(chanName);
				if (locator.safe(false).isThreadUri(uri)) {
					boardName = locator.safe(false).getBoardName(uri);
					threadNumber = locator.safe(false).getThreadNumber(uri);
					postNumber = locator.safe(false).getPostNumber(uri);
					success = true;
				} else if (locator.safe(false).isBoardUri(uri)) {
					boardName = locator.safe(false).getBoardName(uri);
					threadNumber = null;
					postNumber = null;
					success = true;
				}
				if (success) {
					if (threadNumber == null) {
						callback.onSelectBoard(chanName, boardName, false);
					} else {
						callback.onSelectThread(chanName, boardName, threadNumber, postNumber, null, false);
					}
					clearTextAndHideKeyboard();
					return;
				}
			}
		}
		ToastUtils.show(context, R.string.enter_valid_data);
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		onSearchClick();
		return true;
	}

	public void invalidateItems(boolean pages, boolean favorites) {
		if (pages) {
			this.pages.clear();
			if (pagesListMode != Preferences.PAGES_LIST_MODE_HIDE_PAGES) {
				updateListPages();
			}
		}
		if (favorites) {
			this.favorites.clear();
			updateListFavorites();
		}
		switch (pagesListMode) {
			case Preferences.PAGES_LIST_MODE_PAGES_FIRST: {
				categoriesOrder = CategoriesOrder.PAGES_FIRST;
				break;
			}
			case Preferences.PAGES_LIST_MODE_FAVORITES_FIRST: {
				categoriesOrder = CategoriesOrder.FAVORITES_FIRST;
				break;
			}
			case Preferences.PAGES_LIST_MODE_HIDE_PAGES: {
				categoriesOrder = CategoriesOrder.HIDE_PAGES;
				break;
			}
			default: {
				categoriesOrder = null;
				break;
			}
		}
		notifyDataSetChanged();
	}

	private void updateListPages() {
		boolean mergeChans = this.mergeChans;
		Collection<Page> allPages = callback.obtainDrawerPages();
		ArrayList<Page> pages = new ArrayList<>();
		for (Page page : allPages) {
			if (mergeChans || page.chanName.equals(chanName)) {
				if (page.threadNumber != null || !ChanConfiguration.get(page.chanName)
						.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
					pages.add(page);
				}
			}
		}
		if (pages.size() > 0) {
			Collections.sort(pages);
			this.pages.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_CLOSE_ALL,
					ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0),
					context.getString(R.string.open_pages__noun)));
			for (Page page : pages) {
				if (page.threadNumber != null) {
					this.pages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							page.threadNumber, page.threadTitle));
				} else {
					this.pages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							null, ChanConfiguration.get(page.chanName).getBoardTitle(page.boardName)));
				}
			}
		}
	}

	private void updateListFavorites() {
		boolean mergeChans = this.mergeChans;
		FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
		ArrayList<FavoritesStorage.FavoriteItem> favoriteBoards = favoritesStorage.getBoards(mergeChans
				? null : chanName);
		ArrayList<FavoritesStorage.FavoriteItem> favoriteThreads = favoritesStorage.getThreads(mergeChans
				? null : chanName);
		boolean addSection = true;
		for (int i = 0; i < favoriteThreads.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteThreads.get(i);
			if (!ChanManager.getInstance().isAvailableChanName(favoriteItem.chanName)) {
				continue;
			}
			if (mergeChans || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					if (watcherSupportSet.contains(favoriteItem.chanName)
							|| mergeChans && !watcherSupportSet.isEmpty()) {
						favorites.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_FAVORITES_MENU,
								ResourceUtils.getResourceId(context, R.attr.iconButtonMore, 0),
								context.getString(R.string.favorite_threads)));
					} else {
						favorites.add(new ListItem(ListItem.Type.SECTION, null, null, null,
								context.getString(R.string.favorite_threads)));
					}
					addSection = false;
				}
				ListItem listItem = new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName,
						favoriteItem.boardName, favoriteItem.threadNumber, favoriteItem.title);
				if (watcherSupportSet.contains(favoriteItem.chanName)) {
					WatcherService.TemporalCountData temporalCountData = watcherServiceClient
							.countNewPosts(favoriteItem);
					listItem.watcherPostsCountDifference = temporalCountData.postsCountDifference;
					listItem.watcherHasNewPosts = temporalCountData.hasNewPosts;
					listItem.watcherIsError = temporalCountData.isError;
				}
				favorites.add(listItem);
			}
		}
		addSection = true;
		for (int i = 0; i < favoriteBoards.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteBoards.get(i);
			if (!ChanManager.getInstance().isAvailableChanName(favoriteItem.chanName)) {
				continue;
			}
			if (mergeChans || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					favorites.add(new ListItem(ListItem.Type.SECTION, null, null, null,
							context.getString(R.string.favorite_boards)));
					addSection = false;
				}
				favorites.add(new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName, favoriteItem.boardName,
						null, ChanConfiguration.get(favoriteItem.chanName).getBoardTitle(favoriteItem.boardName)));
			}
		}
	}

	private String formatBoardThreadTitle(boolean threadItem, String boardName, String threadNumber, String title) {
		if (threadItem) {
			if (!StringUtils.isEmptyOrWhitespace(title)) {
				return title;
			} else {
				return StringUtils.formatThreadTitle(chanName, boardName, threadNumber);
			}
		} else {
			return StringUtils.formatBoardTitle(chanName, boardName, title);
		}
	}

	private static class ListItem {
		public enum Type {HEADER, RESTART, SECTION, PAGE, FAVORITE, MENU, CHAN}

		public static final ListItem HEADER = new ListItem(Type.HEADER, null, null, null, null);
		public static final ListItem RESTART = new ListItem(Type.RESTART, null, null, null, null);

		public final long id;
		public final Type type;
		public final int data;
		public final boolean iconChan;
		public final int iconResId;
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String title;

		private int watcherPostsCountDifference = 0;
		private boolean watcherHasNewPosts = false;
		private boolean watcherIsError = false;

		private static long nextItemId = 0;

		private static long getNextItemId() {
			return nextItemId++;
		}

		private ListItem(Type type, int data, boolean iconChan, int iconResId,
				String chanName, String boardName, String threadNumber, String title) {
			id = getNextItemId();
			this.type = type;
			this.data = data;
			this.iconChan = iconChan;
			this.iconResId = iconResId;
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.title = title;
		}

		public ListItem(Type type, int data, int iconResId,
				String chanName, String boardName, String threadNumber, String title) {
			this(type, data, false, iconResId, chanName, boardName, threadNumber, title);
		}

		public ListItem(Type type, int data, String chanName, String boardName, String threadNumber, String title) {
			this(type, data, true, 0, chanName, boardName, threadNumber, title);
		}

		public ListItem(Type type, String chanName, String boardName, String threadNumber, String title) {
			this(type, 0, 0, chanName, boardName, threadNumber, title);
		}

		public ListItem(Type type, int data, int iconResId, String title) {
			this(type, data, false, iconResId, null, null, null, title);
		}

		public boolean isThreadItem() {
			return threadNumber != null;
		}

		public boolean compare(String chanName, String boardName, String threadNumber) {
			return StringUtils.equals(this.chanName, chanName) && StringUtils.equals(this.boardName, boardName)
					&& StringUtils.equals(this.threadNumber, threadNumber);
		}
	}

	private final View.OnClickListener closeButtonListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			ListItem listItem = getItemFromChild(v);
			if (listItem != null && listItem.type == ListItem.Type.PAGE) {
				callback.onClosePage(listItem.chanName, listItem.boardName, listItem.threadNumber);
			}
		}
	};

	private static final int SECTION_ACTION_CLOSE_ALL = 0;
	private static final int SECTION_ACTION_FAVORITES_MENU = 1;

	private static final int FAVORITES_MENU_REFRESH = 1;
	private static final int FAVORITES_MENU_CLEAR_DELETED = 2;

	private final View.OnClickListener sectionButtonListener = new View.OnClickListener() {
		@SuppressLint("NewApi")
		@Override
		public void onClick(View v) {
			ListItem listItem = getItemFromChild(v);
			if (listItem != null && listItem.type == ListItem.Type.SECTION) {
				switch (listItem.data) {
					case SECTION_ACTION_CLOSE_ALL: {
						callback.onCloseAllPages();
						break;
					}
					case SECTION_ACTION_FAVORITES_MENU: {
						boolean hasEnabled = false;
						ArrayList<FavoritesStorage.FavoriteItem> deleteFavoriteItems = new ArrayList<>();
						FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
						for (ListItem itListItem : favorites) {
							if (itListItem.isThreadItem()) {
								FavoritesStorage.FavoriteItem favoriteItem = favoritesStorage.getFavorite
										(itListItem.chanName, itListItem.boardName, itListItem.threadNumber);
								if (favoriteItem != null) {
									hasEnabled |= favoriteItem.watcherEnabled;
									WatcherService.TemporalCountData temporalCountData =
											watcherServiceClient.countNewPosts(favoriteItem);
									if (temporalCountData.postsCountDifference ==
											WatcherService.POSTS_COUNT_DIFFERENCE_DELETED) {
										deleteFavoriteItems.add(favoriteItem);
									}
								}
							}
						}
						PopupMenu popupMenu;
						if (C.API_LOLLIPOP_MR1) {
							int resId = ResourceUtils.getResourceId(context, android.R.attr.popupTheme, 0);
							Context context = v.getContext();
							Context popupContext = resId != 0 ? new ContextThemeWrapper(context, resId) : context;
							popupMenu = new PopupMenu(popupContext, v, Gravity.END, 0, R.style.Widget_OverlapPopupMenu);
						} else if (C.API_KITKAT) {
							popupMenu = new PopupMenu(v.getContext(), v, Gravity.END);
						} else {
							popupMenu = new PopupMenu(context, v);
						}
						popupMenu.getMenu().add(0, FAVORITES_MENU_REFRESH, 0, R.string.refresh)
								.setEnabled(hasEnabled);
						popupMenu.getMenu().add(0, FAVORITES_MENU_CLEAR_DELETED, 0, R.string.clear_deleted)
								.setEnabled(!deleteFavoriteItems.isEmpty());
						popupMenu.setOnMenuItemClickListener(item -> {
							switch (item.getItemId()) {
								case FAVORITES_MENU_REFRESH: {
									watcherServiceClient.update();
									return true;
								}
								case FAVORITES_MENU_CLEAR_DELETED: {
									StringBuilder builder = new StringBuilder(context
											.getString(R.string.threads_will_be_deleted__sentence));
									builder.append("\n");
									for (FavoritesStorage.FavoriteItem favoriteItem : deleteFavoriteItems) {
										builder.append("\n\u2022 ").append(formatBoardThreadTitle(true,
												favoriteItem.boardName, favoriteItem.threadNumber, favoriteItem.title));
									}
									AlertDialog dialog = new AlertDialog.Builder(context)
											.setMessage(builder)
											.setNegativeButton(android.R.string.cancel, null)
											.setPositiveButton(android.R.string.ok, (d, which) -> {
												for (FavoritesStorage.FavoriteItem favoriteItem : deleteFavoriteItems) {
													favoritesStorage.remove(favoriteItem.chanName,
															favoriteItem.boardName, favoriteItem.threadNumber);
												}
											})
											.show();
									configurationLock.lockConfiguration(dialog);
									return true;
								}
							}
							return false;
						});
						popupMenu.show();
						break;
					}
				}
			}
		}
	};

	private enum ViewType {
		HEADER(false, false, false),
		RESTART(false, false, false),
		SECTION(false, false, false),
		SECTION_BUTTON(true, false, false),
		ITEM(false, false, false),
		ITEM_ICON(true, false, false),
		WATCHER(false, true, false),
		WATCHER_ICON(true, true, false),
		CLOSEABLE(false, false, true),
		CLOSEABLE_ICON(true, false, true);

		public final boolean icon;
		public final boolean watcher;
		public final boolean closeable;

		ViewType(boolean icon, boolean watcher, boolean closeable) {
			this.icon = icon;
			this.watcher = watcher;
			this.closeable = closeable;
		}
	}

	@Override
	public int getItemViewType(int position) {
		ListItem listItem = getItem(position);
		ViewType viewType;
		switch (listItem.type) {
			case HEADER: {
				viewType = ViewType.HEADER;
				break;
			}
			case RESTART: {
				viewType = ViewType.RESTART;
				break;
			}
			case SECTION: {
				viewType = listItem.iconChan || listItem.iconResId != 0
						? ViewType.SECTION_BUTTON : ViewType.SECTION;
				break;
			}
			case PAGE: {
				viewType = mergeChans && C.API_LOLLIPOP ? ViewType.CLOSEABLE_ICON : ViewType.CLOSEABLE;
				break;
			}
			case FAVORITE: {
				if (listItem.threadNumber != null) {
					boolean watcherSupported = watcherSupportSet.contains(listItem.chanName);
					if (mergeChans && C.API_LOLLIPOP) {
						viewType = watcherSupported ? ViewType.WATCHER_ICON : ViewType.ITEM_ICON;
					} else {
						viewType = watcherSupported ? ViewType.WATCHER : ViewType.ITEM;
					}
				} else {
					viewType = mergeChans && C.API_LOLLIPOP ? ViewType.ITEM_ICON : ViewType.ITEM;
				}
				break;
			}
			case MENU: {
				viewType = ViewType.ITEM_ICON;
				break;
			}
			case CHAN: {
				viewType = C.API_LOLLIPOP ? ViewType.ITEM_ICON : ViewType.ITEM;
				break;
			}
			default: {
				throw new IllegalStateException();
			}
		}
		return viewType.ordinal();
	}

	@SuppressWarnings("unchecked")
	private final List<ListItem>[] categoriesArray = new List[2];

	private int prepareCategoriesArray() {
		switch (categoriesOrder) {
			case PAGES_FIRST: {
				categoriesArray[0] = pages;
				categoriesArray[1] = favorites;
				return 2;
			}
			case FAVORITES_FIRST: {
				categoriesArray[0] = favorites;
				categoriesArray[1] = pages;
				return 2;
			}
			case HIDE_PAGES: {
				categoriesArray[0] = favorites;
				return 1;
			}
			default: {
				return 0;
			}
		}
	}

	@Override
	public int getItemCount() {
		int count = showRestartButton ? 2 : 1;
		if (chanSelectMode) {
			count += chans.size();
		} else {
			int arraySize = prepareCategoriesArray();
			List<ListItem>[] categoriesArray = this.categoriesArray;
			for (int i = 0; i < arraySize; i++) {
				count += categoriesArray[i].size();
			}
			count += menu.size();
		}
		return count;
	}

	private ListItem getItem(int position) {
		if (position == 0) {
			return ListItem.HEADER;
		}
		position--;
		if (showRestartButton) {
			if (position == 0) {
				return ListItem.RESTART;
			}
			position--;
		}
		if (position >= 0) {
			if (chanSelectMode) {
				if (position < chans.size()) {
					return chans.get(position);
				}
			} else {
				int arraySize = prepareCategoriesArray();
				List<ListItem>[] categoriesArray = this.categoriesArray;
				for (int i = 0; i < arraySize; i++) {
					List<ListItem> listItems = categoriesArray[i];
					if (position < listItems.size()) {
						return listItems.get(position);
					}
					position -= listItems.size();
					if (position < 0) {
						throw new IndexOutOfBoundsException();
					}
				}
				if (position < menu.size()) {
					return menu.get(position);
				}
			}
		}
		throw new IndexOutOfBoundsException();
	}

	@Override
	public long getItemId(int position) {
		return getItem(position).id;
	}

	private TextView makeCommonTextView(boolean section) {
		TextView textView = new TextView(context, null, C.API_LOLLIPOP ? android.R.attr.textAppearanceListItem
				: android.R.attr.textAppearance);
		ViewUtils.setTextSizeScaled(textView, C.API_LOLLIPOP ? 14 : 16);
		textView.setGravity(Gravity.CENTER_VERTICAL);
		textView.setEllipsize(TextUtils.TruncateAt.END);
		textView.setSingleLine(true);
		if (C.API_LOLLIPOP) {
			textView.setTypeface(GraphicsUtils.TYPEFACE_MEDIUM);
			int color = textView.getTextColors().getDefaultColor();
			if (section) {
				color &= 0x5effffff;
			} else {
				color &= 0xddffffff;
			}
			textView.setTextColor(color);
		}
		return textView;
	}

	private ViewHolder createItem(ViewType viewType, float density) {
		int size = (int) (48f * density);
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.HORIZONTAL);
		linearLayout.setGravity(Gravity.CENTER_VERTICAL);
		ImageView iconView = null;
		if (viewType.icon) {
			iconView = new ImageView(context);
			iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
			linearLayout.addView(iconView, (int) (24f * density), size);
			if (C.API_LOLLIPOP) {
				iconView.setImageTintList(ColorStateList.valueOf(drawerIconColor));
			}
		}
		TextView textView = makeCommonTextView(false);
		linearLayout.addView(textView, new LinearLayout.LayoutParams(0, size, 1));
		WatcherView watcherView = null;
		if (viewType.watcher) {
			watcherView = new WatcherView(context, watcherViewColorSet);
			watcherView.setOnClickListener(watcherClickListener);
			linearLayout.addView(watcherView, size, size);
		}
		if (!viewType.watcher && viewType.closeable) {
			ImageView closeView = new ImageView(context);
			closeView.setScaleType(ImageView.ScaleType.CENTER);
			closeView.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0));
			if (C.API_LOLLIPOP) {
				closeView.setImageTintList(ResourceUtils.getColorStateList(closeView.getContext(),
						android.R.attr.textColorPrimary));
			}
			closeView.setBackgroundResource(ResourceUtils.getResourceId(context,
					android.R.attr.borderlessButtonStyle, android.R.attr.background, 0));
			linearLayout.addView(closeView, size, size);
			closeView.setOnClickListener(closeButtonListener);
		}
		int layoutLeftDp = 0;
		int layoutRightDp = 0;
		int textLeftDp;
		int textRightDp;
		if (C.API_LOLLIPOP) {
			textLeftDp = 16;
			textRightDp = 16;
			if (viewType.icon) {
				layoutLeftDp = 16;
				textLeftDp = 32;
			}
			if (viewType.watcher || viewType.closeable) {
				layoutRightDp = 4;
				textRightDp = 8;
			}
		} else {
			textLeftDp = 8;
			textRightDp = 8;
			if (viewType.icon) {
				layoutLeftDp = 8;
				textLeftDp = 6;
				textView.setAllCaps(true);
			}
			if (viewType.watcher || viewType.closeable) {
				layoutRightDp = 0;
				textRightDp = 0;
			}
		}
		linearLayout.setPadding((int) (layoutLeftDp * density), 0, (int) (layoutRightDp * density), 0);
		textView.setPadding((int) (textLeftDp * density), 0, (int) (textRightDp * density), 0);
		ViewUtils.setSelectableItemBackground(linearLayout);
		linearLayout.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT));
		return new ViewHolder(linearLayout, iconView, textView, watcherView);
	}

	private ViewHolder createSection(ViewGroup parent, boolean button, float density) {
		if (C.API_LOLLIPOP) {
			LinearLayout linearLayout = new LinearLayout(context);
			linearLayout.setOrientation(LinearLayout.VERTICAL);
			LinearLayout linearLayout2 = new LinearLayout(context);
			linearLayout2.setOrientation(LinearLayout.HORIZONTAL);
			linearLayout.addView(linearLayout2, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			TextView textView = makeCommonTextView(true);
			LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams
					(0, (int) (32f * density), 1);
			layoutParams.setMargins((int) (16f * density), (int) (8f * density),
					(int) (16f * density), (int) (8f * density));
			linearLayout2.addView(textView, layoutParams);
			ImageView imageView = null;
			if (button) {
				imageView = new ImageView(context);
				imageView.setScaleType(ImageView.ScaleType.CENTER);
				imageView.setBackgroundResource(ResourceUtils.getResourceId(context,
						android.R.attr.borderlessButtonStyle, android.R.attr.background, 0));
				imageView.setOnClickListener(sectionButtonListener);
				imageView.setImageTintList(textView.getTextColors());
				int size = (int) (48f * density);
				layoutParams = new LinearLayout.LayoutParams(size, size);
				layoutParams.rightMargin = (int) (4f * density);
				linearLayout2.addView(imageView, layoutParams);
			}
			linearLayout.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
					RecyclerView.LayoutParams.WRAP_CONTENT));
			return new ViewHolder(linearLayout, imageView, textView, null);
		} else {
			View view = LayoutInflater.from(context).inflate(ResourceUtils.getResourceId(context,
					android.R.attr.preferenceCategoryStyle, android.R.attr.layout,
					android.R.layout.preference_category), parent, false);
			TextView textView = view.findViewById(android.R.id.title);
			ImageView imageView = null;
			if (button) {
				int measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
				view.measure(measureSpec, measureSpec);
				int size = view.getMeasuredHeight();
				if (size == 0) {
					size = (int) (32f * density);
				}
				FrameLayout frameLayout = new FrameLayout(context);
				frameLayout.addView(view);
				view = frameLayout;
				imageView = new ImageView(context);
				imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
				int padding = (int) (4f * density);
				imageView.setPadding(padding, padding, padding, padding);
				frameLayout.addView(imageView, new FrameLayout.LayoutParams
						((int) (48f * density), size, Gravity.END));
				View buttonView = new View(context);
				buttonView.setBackgroundResource(ResourceUtils.getResourceId(context,
						android.R.attr.selectableItemBackground, 0));
				buttonView.setOnClickListener(sectionButtonListener);
				frameLayout.addView(buttonView, FrameLayout.LayoutParams.MATCH_PARENT,
						FrameLayout.LayoutParams.MATCH_PARENT);
			}
			view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
					RecyclerView.LayoutParams.WRAP_CONTENT));
			return new ViewHolder(view, imageView, textView, null);
		}
	}

	private final ListViewUtils.ClickCallback<Void, ViewHolder> clickCallback = (holder, position, item, longClick) -> {
		if (longClick) {
			return onItemLongClick(holder);
		} else {
			onItemClick(position);
			return true;
		}
	};

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		float density = ResourceUtils.obtainDensity(context);
		ViewType enumViewType = ViewType.values()[viewType];
		switch (enumViewType) {
			case HEADER: {
				return new ViewHolder(headerView, null, null, null);
			}
			case RESTART: {
				return new ViewHolder(restartView, null, null, null);
			}
			case SECTION:
			case SECTION_BUTTON: {
				return createSection(parent, enumViewType.icon, density);
			}
			case ITEM:
			case ITEM_ICON:
			case WATCHER:
			case WATCHER_ICON:
			case CLOSEABLE:
			case CLOSEABLE_ICON: {
				return ListViewUtils.bind(createItem(enumViewType, density), true, null, clickCallback);
			}
			default: {
				throw new IllegalStateException();
			}
		}
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		ListItem listItem = getItem(position);
		switch (listItem.type) {
			case HEADER:
			case RESTART: {
				// Do nothing
				break;
			}
			case PAGE:
			case FAVORITE: {
				holder.text.setText(formatBoardThreadTitle(listItem.isThreadItem(),
						listItem.boardName, listItem.threadNumber, listItem.title));
				if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem() &&
						watcherSupportSet.contains(listItem.chanName)) {
					WatcherService.WatcherItem watcherItem = watcherServiceClient.getItem(listItem.chanName,
							listItem.boardName, listItem.threadNumber);
					updateWatcherItem(holder, watcherItem != null ? watcherItem.getLastState()
							: WatcherService.State.DISABLED);
				}
				break;
			}
			case SECTION:
			case MENU:
			case CHAN: {
				holder.text.setText(listItem.title);
				break;
			}
		}
		if (holder != null && holder.icon != null) {
			if (listItem.iconChan) {
				if (!chanIcons.containsKey(listItem.chanName)) {
					ChanIconDrawable drawable = ChanManager.getInstance().getIcon(listItem.chanName);
					chanIcons.put(listItem.chanName, drawable);
				}
				ChanIconDrawable chanIcon = chanIcons.get(listItem.chanName);
				holder.icon.setImageDrawable(chanIcon != null ? chanIcon.newInstance() : null);
			} else if (listItem.iconResId != 0) {
				holder.icon.setImageResource(listItem.iconResId);
			} else {
				throw new IllegalStateException();
			}
		}
	}

	public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnTouchListener {
		public final ImageView icon;
		public final TextView text;
		public final WatcherView watcher;

		public ViewHolder(View itemView, ImageView icon, TextView text, WatcherView watcher) {
			super(itemView);

			this.text = text;
			this.icon = icon;
			this.watcher = watcher;

			itemView.setOnTouchListener(this);
		}

		private ColorStateList originalTextColors;
		private ColorStateList originalTintColors;

		public void setDragging(boolean dragging, int activeColor) {
			if (dragging) {
				if (originalTextColors == null) {
					originalTextColors = text.getTextColors();
				}
				text.setTextColor(activeColor);
				if (C.API_LOLLIPOP && icon != null) {
					if (originalTintColors == null) {
						originalTintColors = icon.getImageTintList();
					}
					icon.setImageTintList(ColorStateList.valueOf(activeColor));
				}
			} else {
				if (originalTextColors != null) {
					text.setTextColor(originalTextColors);
				}
				if (C.API_LOLLIPOP && icon != null && originalTintColors != null) {
					icon.setImageTintList(originalTintColors);
				}
			}
		}

		private boolean multipleFingersCountingTime = false;
		private long multipleFingersTime;
		private long multipleFingersStartTime;

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouch(View v, MotionEvent event) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					multipleFingersCountingTime = false;
					multipleFingersStartTime = 0L;
					multipleFingersTime = 0L;
					break;
				}
				case MotionEvent.ACTION_POINTER_DOWN: {
					if (!multipleFingersCountingTime) {
						multipleFingersCountingTime = true;
						multipleFingersStartTime = SystemClock.elapsedRealtime();
					}
					break;
				}
				case MotionEvent.ACTION_POINTER_UP: {
					if (event.getPointerCount() <= 2) {
						if (multipleFingersCountingTime) {
							multipleFingersCountingTime = false;
							multipleFingersTime += SystemClock.elapsedRealtime() - multipleFingersStartTime;
						}
					}
					break;
				}
			}
			return false;
		}

		public boolean isMultipleFingers() {
			long time = multipleFingersTime;
			if (multipleFingersCountingTime) {
				time += SystemClock.elapsedRealtime() - multipleFingersStartTime;
			}
			return time >= ViewConfiguration.getLongPressTimeout() / 10;
		}
	}

	private ListItem getItemFromChild(View child) {
		View view = ListViewUtils.getRootViewInList(child);
		ViewHolder holder = ListViewUtils.getViewHolder(view, ViewHolder.class);
		int position = holder.getAdapterPosition();
		return position >= 0 ? getItem(position) : null;
	}

	private boolean needDivider(ListItem current, ListItem next) {
		return current.type == ListItem.Type.HEADER || current.type == ListItem.Type.RESTART ||
				current.type != ListItem.Type.CHAN && next.type == ListItem.Type.CHAN ||
				current.type != ListItem.Type.MENU && next.type == ListItem.Type.MENU ||
				current.type == ListItem.Type.MENU && current.data == MENU_ITEM_BOARDS &&
						(next.type != ListItem.Type.MENU || next.data != MENU_ITEM_USER_BOARDS) ||
				current.type == ListItem.Type.MENU && current.data == MENU_ITEM_USER_BOARDS;
	}

	private DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		float density = ResourceUtils.obtainDensity(context);
		if (C.API_LOLLIPOP) {
			int padding = (int) (8f * density);
			ListItem current = getItem(position);
			ListItem next = position + 1 < getItemCount() ? getItem(position + 1) : null;
			if (next == null) {
				return configuration.need(false).vertical(0, 0);
			} else if (next.type == ListItem.Type.SECTION) {
				return configuration.need(true).vertical(padding, 0);
			} else if (needDivider(current, next)) {
				return configuration.need(true).vertical(padding, padding);
			} else {
				return configuration.need(false).vertical(0, 0);
			}
		} else {
			int height = (int) (2f * density);
			ListItem current = getItem(position);
			ListItem next = position + 1 < getItemCount() ? getItem(position + 1) : null;
			if (current.type == ListItem.Type.SECTION || next != null && next.type == ListItem.Type.SECTION) {
				return configuration.need(false).height(0);
			} else if (next == null) {
				return configuration.need(true).height(0);
			} else if (needDivider(current, next)) {
				return configuration.need(true).height(height);
			} else {
				return configuration.need(true).height(0);
			}
		}
	}

	private void updateWatcherItem(ViewHolder holder, WatcherService.State state) {
		ListItem listItem = getItem(holder.getAdapterPosition());
		holder.watcher.setPostsCountDifference(listItem.watcherPostsCountDifference,
				listItem.watcherHasNewPosts, listItem.watcherIsError);
		// Null state means state not changed
		if (state != null) {
			holder.watcher.setWatcherState(state);
		}
	}

	private final View.OnClickListener watcherClickListener = v -> {
		DrawerForm.ListItem listItem = getItemFromChild(v);
		if (listItem != null) {
			FavoritesStorage.getInstance().toggleWatcher(listItem.chanName, listItem.boardName, listItem.threadNumber);
		}
	};

	@Override
	public void onWatcherUpdate(WatcherService.WatcherItem watcherItem, WatcherService.State state) {
		if ((mergeChans || watcherItem.chanName.equals(chanName))
				&& watcherSupportSet.contains(watcherItem.chanName)) {
			ListItem targetItem = null;
			for (ListItem listItem : favorites) {
				if (listItem.compare(watcherItem.chanName, watcherItem.boardName, watcherItem.threadNumber)) {
					targetItem = listItem;
					break;
				}
			}
			if (targetItem != null) {
				targetItem.watcherPostsCountDifference = watcherItem.getPostsCountDifference();
				targetItem.watcherHasNewPosts = watcherItem.hasNewPosts();
				targetItem.watcherIsError = watcherItem.isError();
				int childCount = recyclerView.getChildCount();
				for (int i = 0; i < childCount; i++) {
					ViewHolder holder = (ViewHolder) recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
					int position = holder.getAdapterPosition();
					if (position >= 0 && targetItem == getItem(position)) {
						updateWatcherItem(holder, state);
						break;
					}
				}
			}
		}
	}

	private final SortableHelper.DragState chanDragState = new SortableHelper.DragState();
	private final SortableHelper.DragState favoriteDragState = new SortableHelper.DragState();

	@Override
	public void onDragStart(ViewHolder holder) {
		chanDragState.reset();
		favoriteDragState.reset();
		holder.setDragging(true, watcherViewColorSet.enabledColor);
		callback.onDraggingStateChanged(true);
	}

	@Override
	public void onDragFinish(ViewHolder holder, boolean cancelled) {
		if (!cancelled) {
			int chanMovedTo = chanDragState.getMovedTo();
			int favoriteMovedTo = favoriteDragState.getMovedTo();
			if (chanMovedTo >= 0) {
				ArrayList<String> chanNames = new ArrayList<>();
				for (DrawerForm.ListItem listItem : chans) {
					chanNames.add(listItem.chanName);
				}
				ChanManager.getInstance().setChansOrder(chanNames);
				// Regroup favorite threads
				if (mergeChans) {
					invalidateItems(false, true);
				}
			} else if (favoriteMovedTo >= 0) {
				// "to" is always > 0 since favorites list contains header
				DrawerForm.ListItem listItem = favorites.get(favoriteMovedTo);
				DrawerForm.ListItem afterListItem = favorites.get(favoriteMovedTo - 1);
				FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
				FavoritesStorage.FavoriteItem favoriteItem = favoritesStorage.getFavorite(listItem.chanName,
						listItem.boardName, listItem.threadNumber);
				FavoritesStorage.FavoriteItem afterFavoriteItem = afterListItem.type ==
						DrawerForm.ListItem.Type.FAVORITE && afterListItem.chanName.equals(favoriteItem.chanName)
						? favoritesStorage.getFavorite(afterListItem.chanName, afterListItem.boardName,
						afterListItem.threadNumber) : null;
				favoritesStorage.moveAfter(favoriteItem, afterFavoriteItem);
			}
		}
		holder.setDragging(false, 0);
		callback.onDraggingStateChanged(false);
	}

	@Override
	public boolean onDragCanMove(ViewHolder fromHolder, ViewHolder toHolder) {
		DrawerForm.ListItem from = getItem(fromHolder.getAdapterPosition());
		DrawerForm.ListItem to = getItem(toHolder.getAdapterPosition());
		return from.type == to.type && (from.type == DrawerForm.ListItem.Type.CHAN ||
				from.type == DrawerForm.ListItem.Type.FAVORITE && StringUtils.equals(from.chanName, to.chanName) &&
						(from.threadNumber == null) == (to.threadNumber == null));
	}

	@Override
	public boolean onDragMove(ViewHolder fromHolder, ViewHolder toHolder) {
		int fromIndex = fromHolder.getAdapterPosition();
		int toIndex = toHolder.getAdapterPosition();
		DrawerForm.ListItem from = getItem(fromIndex);
		DrawerForm.ListItem to = getItem(toIndex);
		int chansFrom = chans.indexOf(from);
		int chansTo = chans.indexOf(to);
		int favoritesFrom = favorites.indexOf(from);
		int favoritesTo = favorites.indexOf(to);
		ArrayList<DrawerForm.ListItem> workList = null;
		SortableHelper.DragState dragState = null;
		int workFrom = -1;
		int workTo = -1;
		if (chansFrom >= 0 && chansTo >= 0) {
			workList = chans;
			dragState = chanDragState;
			workFrom = chansFrom;
			workTo = chansTo;
		} else if (favoritesFrom >= 0 && favoritesTo >= 0) {
			workList = favorites;
			dragState = favoriteDragState;
			workFrom = favoritesFrom;
			workTo = favoritesTo;
		}
		if (workList != null && dragState != null) {
			workList.add(workTo, workList.remove(workFrom));
			notifyItemMoved(fromIndex, toIndex);
			dragState.set(workFrom, workTo);
			return true;
		}
		return false;
	}
}

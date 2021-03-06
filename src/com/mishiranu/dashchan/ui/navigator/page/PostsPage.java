package com.mishiranu.dashchan.ui.navigator.page;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.ChanConfiguration;
import chan.content.ChanLocator;
import chan.content.ChanManager;
import chan.content.RedirectException;
import chan.content.model.Posts;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.DeserializePostsTask;
import com.mishiranu.dashchan.content.async.ReadPostsTask;
import com.mishiranu.dashchan.content.model.AttachmentItem;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.HistoryDatabase;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.ui.DrawerForm;
import com.mishiranu.dashchan.ui.SeekBarForm;
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.adapter.PostsAdapter;
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit;
import com.mishiranu.dashchan.ui.navigator.manager.HidePerformer;
import com.mishiranu.dashchan.ui.navigator.manager.ThreadshotPerformer;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.ui.posting.Replyable;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SearchHelper;
import com.mishiranu.dashchan.util.ToastUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PostsLayoutManager;
import com.mishiranu.dashchan.widget.PullableRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class PostsPage extends ListPage implements PostsAdapter.Callback, FavoritesStorage.Observer,
		UiManager.Observer, DeserializePostsTask.Callback, ReadPostsTask.Callback, ActionMode.Callback {
	private enum QueuedRefresh {
		NONE, REFRESH, RELOAD;

		public static QueuedRefresh max(QueuedRefresh queuedRefresh1, QueuedRefresh queuedRefresh2) {
			return values()[Math.max(queuedRefresh1.ordinal(), queuedRefresh2.ordinal())];
		}
	}

	private static class RetainExtra {
		public static final ExtraFactory<RetainExtra> FACTORY = RetainExtra::new;

		public Posts cachedPosts;
		public final ArrayList<PostItem> cachedPostItems = new ArrayList<>();
		public final HashSet<String> userPostNumbers = new HashSet<>();

		public final ArrayList<Integer> searchFoundPosts = new ArrayList<>();
		public boolean searching = false;
		public int searchLastPosition;

		public DialogUnit.StackInstance.State dialogsState;
	}

	private static class ParcelableExtra implements Parcelable {
		public static final ExtraFactory<ParcelableExtra> FACTORY = ParcelableExtra::new;

		public final ArrayList<ReadPostsTask.UserPostPending> userPostPendingList = new ArrayList<>();
		public final HashSet<String> expandedPosts = new HashSet<>();
		public boolean isAddedToHistory = false;
		public boolean hasNewPostDataList = false;
		public QueuedRefresh queuedRefresh = QueuedRefresh.NONE;
		public String threadTitle;
		public String newPostNumber;
		public String scrollToPostNumber;
		public Set<String> selectedItems;

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeList(userPostPendingList);
			dest.writeStringArray(CommonUtils.toArray(expandedPosts, String.class));
			dest.writeByte((byte) (isAddedToHistory ? 1 : 0));
			dest.writeByte((byte) (hasNewPostDataList ? 1 : 0));
			dest.writeString(queuedRefresh.name());
			dest.writeString(threadTitle);
			dest.writeString(newPostNumber);
			dest.writeByte((byte) (selectedItems != null ? 1 : 0));
			if (selectedItems != null) {
				dest.writeStringList(new ArrayList<>(selectedItems));
			}
		}

		public static final Creator<ParcelableExtra> CREATOR = new Creator<ParcelableExtra>() {
			@Override
			public ParcelableExtra createFromParcel(Parcel in) {
				ParcelableExtra parcelableExtra = new ParcelableExtra();
				@SuppressWarnings("unchecked")
				ArrayList<ReadPostsTask.UserPostPending> userPostPendingList = in
						.readArrayList(ParcelableExtra.class.getClassLoader());
				parcelableExtra.userPostPendingList.addAll(userPostPendingList);
				String[] data = in.createStringArray();
				if (data != null) {
					Collections.addAll(parcelableExtra.expandedPosts, data);
				}
				parcelableExtra.isAddedToHistory = in.readByte() != 0;
				parcelableExtra.hasNewPostDataList = in.readByte() != 0;
				parcelableExtra.queuedRefresh = QueuedRefresh.valueOf(in.readString());
				parcelableExtra.threadTitle = in.readString();
				parcelableExtra.newPostNumber = in.readString();
				if (in.readByte() != 0) {
					ArrayList<String> selectedItems = in.createStringArrayList();
					parcelableExtra.selectedItems = selectedItems != null
							? new HashSet<>(selectedItems) : Collections.emptySet();
				}
				return parcelableExtra;
			}

			@Override
			public ParcelableExtra[] newArray(int size) {
				return new ParcelableExtra[size];
			}
		};
	}

	private DeserializePostsTask deserializeTask;
	private ReadPostsTask readTask;

	private Replyable replyable;
	private HidePerformer hidePerformer;
	private Pair<String, Uri> originalThreadData;

	private ActionMode selectionMode;

	private LinearLayout searchController;
	private Button searchTextResult;

	private int autoRefreshInterval = 30;
	private boolean autoRefreshEnabled = false;

	private final ArrayList<String> lastEditedPostNumbers = new ArrayList<>();

	private PostsAdapter getAdapter() {
		return (PostsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		Context context = getContext();
		PullableRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new PostsLayoutManager(recyclerView.getContext()));
		Page page = getPage();
		UiManager uiManager = getUiManager();
		float density = ResourceUtils.obtainDensity(context);
		int dividerPadding = (int) (12f * density);
		hidePerformer = new HidePerformer(context);
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		ChanConfiguration.Board board = getChanConfiguration().safe().obtainBoard(page.boardName);
		if (board.allowPosting) {
			replyable = data -> getUiManager().navigator().navigatePosting(page.chanName, page.boardName,
					page.threadNumber, data);
		} else {
			replyable = null;
		}
		PostsAdapter adapter = new PostsAdapter(this, page.chanName, page.boardName, uiManager,
				replyable, hidePerformer, retainExtra.userPostNumbers, recyclerView);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				(c, position) -> adapter.configureDivider(c, position).horizontal(dividerPadding, dividerPadding)));
		recyclerView.addItemDecoration(adapter.createPostItemDecoration(context, dividerPadding));
		recyclerView.getWrapper().setPullSides(PullableWrapper.Side.BOTH);
		uiManager.observable().register(this);
		hidePerformer.setPostsProvider(adapter);

		Context darkStyledContext = new ContextThemeWrapper(context, R.style.Theme_Main_Dark);
		searchController = new LinearLayout(darkStyledContext);
		searchController.setOrientation(LinearLayout.HORIZONTAL);
		searchController.setGravity(Gravity.CENTER_VERTICAL);
		int buttonPadding = (int) (10f * density);
		searchTextResult = new Button(darkStyledContext, null, android.R.attr.borderlessButtonStyle);
		ViewUtils.setTextSizeScaled(searchTextResult, 11);
		if (!C.API_LOLLIPOP) {
			searchTextResult.setTypeface(null, Typeface.BOLD);
		}
		searchTextResult.setPadding((int) (14f * density), 0, (int) (14f * density), 0);
		searchTextResult.setMinimumWidth(0);
		searchTextResult.setMinWidth(0);
		searchTextResult.setOnClickListener(v -> showSearchDialog());
		searchController.addView(searchTextResult, LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		ImageView backButtonView = new ImageView(darkStyledContext, null, android.R.attr.borderlessButtonStyle);
		backButtonView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		backButtonView.setImageDrawable(getActionBarIcon(R.attr.iconActionBack));
		backButtonView.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
		backButtonView.setOnClickListener(v -> findBack());
		searchController.addView(backButtonView, (int) (48f * density), (int) (48f * density));
		ImageView forwardButtonView = new ImageView(darkStyledContext, null, android.R.attr.borderlessButtonStyle);
		forwardButtonView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		forwardButtonView.setImageDrawable(getActionBarIcon(R.attr.iconActionForward));
		forwardButtonView.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
		forwardButtonView.setOnClickListener(v -> findForward());
		searchController.addView(forwardButtonView, (int) (48f * density), (int) (48f * density));
		if (C.API_LOLLIPOP) {
			for (int i = 0, last = searchController.getChildCount() - 1; i <= last; i++) {
				View view = searchController.getChildAt(i);
				LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
				if (i == 0) {
					layoutParams.leftMargin = (int) (-6f * density);
				}
				if (i == last) {
					layoutParams.rightMargin = (int) (6f * density);
				} else {
					layoutParams.rightMargin = (int) (-6f * density);
				}
			}
		}

		InitRequest initRequest = getInitRequest();
		if (initRequest.threadTitle != null && parcelableExtra.threadTitle == null) {
			parcelableExtra.threadTitle = initRequest.threadTitle;
		}
		if (initRequest.postNumber != null) {
			parcelableExtra.scrollToPostNumber = initRequest.postNumber;
		}
		FavoritesStorage.getInstance().getObservable().register(this);
		parcelableExtra.hasNewPostDataList |= handleNewPostDataList();
		QueuedRefresh queuedRefresh = initRequest.shouldLoad ? QueuedRefresh.REFRESH : QueuedRefresh.NONE;
		parcelableExtra.queuedRefresh = QueuedRefresh.max(parcelableExtra.queuedRefresh, queuedRefresh);
		if (retainExtra.cachedPosts != null && retainExtra.cachedPostItems.size() > 0) {
			String searchSubmitQuery = getInitSearch().submitQuery;
			if (searchSubmitQuery == null) {
				retainExtra.searching = false;
			}
			if (retainExtra.searching && !retainExtra.searchFoundPosts.isEmpty()) {
				setCustomSearchView(searchController);
				updateSearchTitle();
			}
			onDeserializePostsCompleteInternal(true, retainExtra.cachedPosts,
					new ArrayList<>(retainExtra.cachedPostItems), true);
			if (retainExtra.dialogsState != null) {
				uiManager.dialog().restoreState(adapter.getConfigurationSet(), retainExtra.dialogsState);
				retainExtra.dialogsState = null;
			}
		} else {
			retainExtra.searching = false;
			deserializeTask = new DeserializePostsTask(this, page.chanName, page.boardName,
					page.threadNumber, retainExtra.cachedPosts);
			deserializeTask.executeOnExecutor(DeserializePostsTask.THREAD_POOL_EXECUTOR);
			recyclerView.getWrapper().startBusyState(PullableWrapper.Side.BOTH);
			switchView(ViewType.PROGRESS, null);
		}
	}

	@Override
	protected void onResume() {
		queueNextRefresh(true);
	}

	@Override
	protected void onPause() {
		stopRefresh();
	}

	@Override
	protected void onDestroy() {
		if (selectionMode != null) {
			selectionMode.finish();
			selectionMode = null;
		}
		getAdapter().cleanup();
		getUiManager().dialog().closeDialogs(getAdapter().getConfigurationSet().stackInstance);
		getUiManager().observable().unregister(this);
		if (deserializeTask != null) {
			deserializeTask.cancel();
			deserializeTask = null;
		}
		if (readTask != null) {
			readTask.cancel();
			readTask = null;
		}
		FavoritesStorage.getInstance().getObservable().unregister(this);
		setCustomSearchView(null);
	}

	@Override
	protected void onNotifyAllAdaptersChanged() {
		getUiManager().dialog().notifyDataSetChangedToAll(getAdapter().getConfigurationSet().stackInstance);
	}

	@Override
	protected void onHandleNewPostDataList() {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		parcelableExtra.hasNewPostDataList |= handleNewPostDataList();
		if (parcelableExtra.hasNewPostDataList) {
			refreshPosts(true, false);
		}
	}

	@Override
	protected void onScrollToPost(String postNumber) {
		int position = getAdapter().findPositionByPostNumber(postNumber);
		if (position >= 0) {
			getUiManager().dialog().closeDialogs(getAdapter().getConfigurationSet().stackInstance);
			ListViewUtils.smoothScrollToPosition(getRecyclerView(), position);
		}
	}

	@Override
	protected void onRequestStoreExtra(boolean saveToStack) {
		PostsAdapter adapter = getAdapter();
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		retainExtra.dialogsState = adapter.getConfigurationSet().stackInstance.collectState();
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (readTask != null && !saveToStack) {
			boolean reload = readTask.isForceLoadFullThread();
			parcelableExtra.queuedRefresh = QueuedRefresh.max(parcelableExtra.queuedRefresh,
					reload ? QueuedRefresh.RELOAD : QueuedRefresh.REFRESH);
		}
		parcelableExtra.expandedPosts.clear();
		for (PostItem postItem : adapter) {
			if (postItem.isExpanded()) {
				parcelableExtra.expandedPosts.add(postItem.getPostNumber());
			}
		}
		parcelableExtra.selectedItems = null;
		if (selectionMode != null && !saveToStack) {
			ArrayList<PostItem> selected = adapter.getSelectedItems();
			parcelableExtra.selectedItems = new HashSet<>(selected.size());
			for (PostItem postItem : selected) {
				parcelableExtra.selectedItems.add(postItem.getPostNumber());
			}
		}
	}

	@Override
	public String obtainTitle() {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (!StringUtils.isEmptyOrWhitespace(parcelableExtra.threadTitle)) {
			return parcelableExtra.threadTitle;
		} else {
			Page page = getPage();
			return StringUtils.formatThreadTitle(page.chanName, page.boardName, page.threadNumber);
		}
	}

	@Override
	public void onItemClick(View view, PostItem postItem) {
		if (selectionMode != null) {
			getAdapter().toggleItemSelected(postItem);
			selectionMode.setTitle(ResourceUtils.getColonString(getResources(), R.string.selected,
					getAdapter().getSelectedCount()));
			return;
		}
		getUiManager().interaction().handlePostClick(view, postItem, getAdapter());
	}

	@Override
	public boolean onItemLongClick(PostItem postItem) {
		if (selectionMode != null) {
			return false;
		}
		return postItem != null && getUiManager().interaction()
				.handlePostContextMenu(postItem, replyable, true, true, false);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_add_post, 0, R.string.reply)
				.setIcon(getActionBarIcon(R.attr.iconActionAddPost))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_search, 0, R.string.search)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_gallery, 0, R.string.gallery);
		menu.add(0, R.id.menu_select, 0, R.string.select);
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
		SubMenu threadOptions = menu.addSubMenu(0, R.id.menu_thread_options, 0, R.string.thread_options);
		menu.add(0, R.id.menu_star_text, 0, R.string.add_to_favorites);
		menu.add(0, R.id.menu_unstar_text, 0, R.string.remove_from_favorites);
		menu.add(0, R.id.menu_star_icon, 0, R.string.add_to_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionAddToFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_unstar_icon, 0, R.string.remove_from_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionRemoveFromFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_open_original_thread, 0, R.string.open_original);
		menu.add(0, R.id.menu_archive, 0, R.string.archive__verb);

		threadOptions.add(0, R.id.menu_reload, 0, R.string.reload);
		threadOptions.add(0, R.id.menu_auto_refresh, 0, R.string.auto_refreshing).setCheckable(true);
		threadOptions.add(0, R.id.menu_hidden_posts, 0, R.string.hidden_posts);
		threadOptions.add(0, R.id.menu_clear, 0, R.string.clear_deleted);
		threadOptions.add(0, R.id.menu_summary, 0, R.string.summary);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		Page pageHolder = getPage();
		menu.findItem(R.id.menu_add_post).setVisible(replyable != null);
		boolean isFavorite = FavoritesStorage.getInstance().hasFavorite(pageHolder.chanName, pageHolder.boardName,
				pageHolder.threadNumber);
		boolean iconFavorite = ResourceUtils.isTabletOrLandscape(getResources().getConfiguration());
		menu.findItem(R.id.menu_star_text).setVisible(!iconFavorite && !isFavorite);
		menu.findItem(R.id.menu_unstar_text).setVisible(!iconFavorite && isFavorite);
		menu.findItem(R.id.menu_star_icon).setVisible(iconFavorite && !isFavorite);
		menu.findItem(R.id.menu_unstar_icon).setVisible(iconFavorite && isFavorite);
		menu.findItem(R.id.menu_open_original_thread).setVisible(originalThreadData != null);
		menu.findItem(R.id.menu_archive).setVisible(ChanManager.getInstance()
				.canBeArchived(pageHolder.chanName));
		menu.findItem(R.id.menu_auto_refresh).setVisible(Preferences.getAutoRefreshMode()
				== Preferences.AUTO_REFRESH_MODE_SEPARATE).setEnabled(getAdapter().getItemCount() > 0)
				.setChecked(autoRefreshEnabled);
		menu.findItem(R.id.menu_hidden_posts).setEnabled(hidePerformer.hasLocalAutohide());
		menu.findItem(R.id.menu_clear).setEnabled(getAdapter().hasDeletedPosts());
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Page page = getPage();
		PostsAdapter adapter = getAdapter();
		switch (item.getItemId()) {
			case R.id.menu_add_post: {
				getUiManager().navigator().navigatePosting(page.chanName, page.boardName,
						page.threadNumber);
				return true;
			}
			case R.id.menu_gallery: {
				int imageIndex = -1;
				RecyclerView recyclerView = getRecyclerView();
				View child = recyclerView.getChildAt(0);
				GalleryItem.GallerySet gallerySet = getAdapter().getConfigurationSet().gallerySet;
				if (child != null) {
					UiManager uiManager = getUiManager();
					ArrayList<GalleryItem> galleryItems = gallerySet.getItems();
					int position = recyclerView.getChildAdapterPosition(child);
					OUTER: for (int v = 0; v <= 1; v++) {
						for (PostItem postItem : adapter.iterate(v == 0, position)) {
							imageIndex = uiManager.view().findImageIndex(galleryItems, postItem);
							if (imageIndex != -1) {
								break OUTER;
							}
						}
					}
				}
				getUiManager().navigator().navigateGallery(page.chanName, gallerySet, imageIndex,
						null, GalleryOverlay.NavigatePostMode.ENABLED, true);
				return true;
			}
			case R.id.menu_select: {
				selectionMode = startActionMode(this);
				return true;
			}
			case R.id.menu_refresh: {
				refreshPosts(true, false);
				return true;
			}
			case R.id.menu_star_text:
			case R.id.menu_star_icon: {
				FavoritesStorage.getInstance().add(page.chanName, page.boardName,
						page.threadNumber, getParcelableExtra(ParcelableExtra.FACTORY).threadTitle,
						adapter.getExistingPostsCount());
				updateOptionsMenu();
				return true;
			}
			case R.id.menu_unstar_text:
			case R.id.menu_unstar_icon: {
				FavoritesStorage.getInstance().remove(page.chanName, page.boardName,
						page.threadNumber);
				updateOptionsMenu();
				return true;
			}
			case R.id.menu_open_original_thread: {
				String chanName = originalThreadData.first;
				Uri uri = originalThreadData.second;
				ChanLocator locator = ChanLocator.get(chanName);
				String boardName = locator.safe(true).getBoardName(uri);
				String threadNumber = locator.safe(true).getThreadNumber(uri);
				if (threadNumber != null) {
					String threadTitle = getAdapter().getItem(0).getSubjectOrComment();
					getUiManager().navigator().navigatePosts(chanName, boardName, threadNumber, null,
							threadTitle, 0);
				}
				return true;
			}
			case R.id.menu_archive: {
				String threadTitle = null;
				if (adapter.getItemCount() > 0) {
					threadTitle = adapter.getItem(0).getSubjectOrComment();
				}
				getUiManager().dialog().performSendArchiveThread(page.chanName, page.boardName,
						page.threadNumber, threadTitle, getRetainExtra(RetainExtra.FACTORY).cachedPosts);
				return true;
			}
			case R.id.menu_reload: {
				refreshPosts(true, true);
				return true;
			}
			case R.id.menu_auto_refresh: {
				SeekBarForm seekBarForm = new SeekBarForm(true);
				seekBarForm.setConfiguration(Preferences.MIN_AUTO_REFRESH_INTERVAL,
						Preferences.MAX_AUTO_REFRESH_INTERVAL, Preferences.STEP_AUTO_REFRESH_INTERVAL, 1f);
				seekBarForm.setValueFormat(getString(R.string.every_number_sec__format));
				seekBarForm.setCurrentValue(autoRefreshInterval);
				seekBarForm.setSwitchValue(autoRefreshEnabled);
				AlertDialog dialog = new AlertDialog.Builder(getContext())
						.setTitle(R.string.auto_refreshing)
						.setView(seekBarForm.inflate(getContext()))
						.setPositiveButton(android.R.string.ok, (d, which) -> {
							autoRefreshEnabled = seekBarForm.getSwitchValue();
							autoRefreshInterval = seekBarForm.getCurrentValue();
							Posts posts = getRetainExtra(RetainExtra.FACTORY).cachedPosts;
							boolean changed = posts.setAutoRefreshData(autoRefreshEnabled, autoRefreshInterval);
							if (changed) {
								serializePosts();
							}
							queueNextRefresh(true);
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				getUiManager().getConfigurationLock().lockConfiguration(dialog);
				return true;
			}
			case R.id.menu_hidden_posts: {
				ArrayList<String> localAutohide = hidePerformer.getReadableLocalAutohide(getContext());
				final boolean[] checked = new boolean[localAutohide.size()];
				AlertDialog dialog = new AlertDialog.Builder(getContext())
						.setTitle(R.string.remove_rules)
						.setMultiChoiceItems(CommonUtils.toArray(localAutohide, String.class),
								checked, (d, which, isChecked) -> checked[which] = isChecked)
						.setPositiveButton(android.R.string.ok, (d, which) -> {
							boolean hasDeleted = false;
							for (int i = 0, j = 0; i < checked.length; i++, j++) {
								if (checked[i]) {
									hidePerformer.removeLocalAutohide(j--);
									hasDeleted = true;
								}
							}
							if (hasDeleted) {
								adapter.invalidateHidden();
								notifyAllAdaptersChanged();
								hidePerformer.encodeLocalAutohide(getRetainExtra(RetainExtra.FACTORY).cachedPosts);
								serializePosts();
								adapter.preloadPosts(((LinearLayoutManager) getRecyclerView().getLayoutManager())
										.findFirstVisibleItemPosition());
							}
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				getUiManager().getConfigurationLock().lockConfiguration(dialog);
				return true;
			}
			case R.id.menu_clear: {
				AlertDialog dialog = new AlertDialog.Builder(getContext())
						.setMessage(R.string.deleted_posts_will_be_deleted__sentence)
						.setPositiveButton(android.R.string.ok, (d, which) -> {
							RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
							Posts cachedPosts = retainExtra.cachedPosts;
							cachedPosts.clearDeletedPosts();
							ArrayList<PostItem> deletedPostItems = adapter.clearDeletedPosts();
							if (deletedPostItems != null) {
								retainExtra.cachedPostItems.removeAll(deletedPostItems);
								for (PostItem postItem : deletedPostItems) {
									retainExtra.userPostNumbers.remove(postItem.getPostNumber());
								}
								notifyAllAdaptersChanged();
							}
							updateOptionsMenu();
							serializePosts();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				getUiManager().getConfigurationLock().lockConfiguration(dialog);
				return true;
			}
			case R.id.menu_summary: {
				RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
				int files = 0;
				int postsWithFiles = 0;
				int links = 0;
				for (PostItem postItem : getAdapter()) {
					List<AttachmentItem> attachmentItems = postItem.getAttachmentItems();
					if (attachmentItems != null) {
						int itFiles = 0;
						for (AttachmentItem attachmentItem : attachmentItems) {
							AttachmentItem.GeneralType generalType = attachmentItem.getGeneralType();
							switch (generalType) {
								case FILE:
								case EMBEDDED: {
									itFiles++;
									break;
								}
								case LINK: {
									links++;
									break;
								}
							}
						}
						if (itFiles > 0) {
							postsWithFiles++;
							files += itFiles;
						}
					}
				}
				int uniquePosters = retainExtra.cachedPosts!= null ? retainExtra.cachedPosts.getUniquePosters() : -1;
				StringBuilder builder = new StringBuilder();
				String boardName = page.boardName;
				if (boardName != null) {
					builder.append(getString(R.string.board)).append(": ");
					String title = getChanConfiguration().getBoardTitle(boardName);
					builder.append(StringUtils.formatBoardTitle(page.chanName, boardName, title));
					builder.append('\n');
				}
				builder.append(ResourceUtils.getColonString(getResources(), R.string.files__genitive, files));
				builder.append('\n').append(ResourceUtils.getColonString(getResources(),
						R.string.posts_with_files__genitive, postsWithFiles));
				builder.append('\n').append(ResourceUtils.getColonString(getResources(),
						R.string.links_attachments__genitive, links));
				if (uniquePosters > 0) {
					builder.append('\n').append(ResourceUtils.getColonString(getResources(),
							R.string.unique_posters__genitive, uniquePosters));
				}
				AlertDialog dialog = new AlertDialog.Builder(getContext())
						.setTitle(R.string.summary)
						.setMessage(builder)
						.setPositiveButton(android.R.string.ok, null)
						.show();
				getUiManager().getConfigurationLock().lockConfiguration(dialog);
				return true;
			}
		}
		return false;
	}

	@Override
	public void onFavoritesUpdate(FavoritesStorage.FavoriteItem favoriteItem, FavoritesStorage.Action action) {
		switch (action) {
			case ADD:
			case REMOVE: {
				Page page = getPage();
				if (favoriteItem.equals(page.chanName, page.boardName, page.threadNumber)) {
					updateOptionsMenu();
				}
				break;
			}
		}
	}

	@Override
	public void onAppearanceOptionChanged(int what) {
		switch (what) {
			case R.id.menu_spoilers:
			case R.id.menu_my_posts:
			case R.id.menu_sfw_mode: {
				notifyAllAdaptersChanged();
				break;
			}
		}
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		Page page = getPage();
		ChanConfiguration configuration = getChanConfiguration();
		getAdapter().setSelectionModeEnabled(true);
		mode.setTitle(ResourceUtils.getColonString(getResources(),
				R.string.selected, getAdapter().getSelectedCount()));
		ChanConfiguration.Board board = configuration.safe().obtainBoard(page.boardName);
		menu.add(0, R.id.menu_make_threadshot, 0, R.string.make_threadshot)
				.setIcon(getActionBarIcon(R.attr.iconActionMakeThreadshot))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		if (replyable != null) {
			menu.add(0, R.id.menu_reply, 0, R.string.reply)
					.setIcon(getActionBarIcon(R.attr.iconActionPaste))
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}
		if (board.allowDeleting) {
			ChanConfiguration.Deleting deleting = configuration.safe().obtainDeleting(page.boardName);
			if (deleting != null && deleting.multiplePosts) {
				menu.add(0, R.id.menu_delete, 0, R.string.delete)
						.setIcon(getActionBarIcon(R.attr.iconActionDelete))
						.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			}
		}
		if (board.allowReporting) {
			ChanConfiguration.Reporting reporting = configuration.safe().obtainReporting(page.boardName);
			if (reporting != null && reporting.multiplePosts) {
				menu.add(0, R.id.menu_report, 0, R.string.report)
						.setIcon(getActionBarIcon(R.attr.iconActionReport))
						.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			}
		}
		return true;
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		return false;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_make_threadshot: {
				ArrayList<PostItem> postItems = getAdapter().getSelectedItems();
				if (postItems.size() > 0) {
					Page page = getPage();
					String threadTitle = getAdapter().getConfigurationSet().gallerySet.getThreadTitle();
					new ThreadshotPerformer(getRecyclerView(), getUiManager(), page.chanName, page.boardName,
							page.threadNumber, threadTitle, postItems);
				}
				mode.finish();
				return true;
			}
			case R.id.menu_reply: {
				ArrayList<Replyable.ReplyData> data = new ArrayList<>();
				for (PostItem postItem : getAdapter().getSelectedItems()) {
					data.add(new Replyable.ReplyData(postItem.getPostNumber(), null));
				}
				if (data.size() > 0) {
					replyable.onRequestReply(CommonUtils.toArray(data, Replyable.ReplyData.class));
				}
				mode.finish();
				return true;
			}
			case R.id.menu_delete: {
				ArrayList<PostItem> postItems = getAdapter().getSelectedItems();
				ArrayList<String> postNumbers = new ArrayList<>();
				for (PostItem postItem : postItems) {
					if (!postItem.isDeleted()) {
						postNumbers.add(postItem.getPostNumber());
					}
				}
				if (postNumbers.size() > 0) {
					Page page = getPage();
					getUiManager().dialog().performSendDeletePosts(page.chanName, page.boardName,
							page.threadNumber, postNumbers);
				}
				mode.finish();
				return true;
			}
			case R.id.menu_report: {
				ArrayList<PostItem> postItems = getAdapter().getSelectedItems();
				ArrayList<String> postNumbers = new ArrayList<>();
				for (PostItem postItem : postItems) {
					if (!postItem.isDeleted()) {
						postNumbers.add(postItem.getPostNumber());
					}
				}
				if (postNumbers.size() > 0) {
					Page page = getPage();
					getUiManager().dialog().performSendReportPosts(page.chanName, page.boardName,
							page.threadNumber, postNumbers);
				}
				mode.finish();
				return true;
			}
		}
		return false;
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {
		getAdapter().setSelectionModeEnabled(false);
		selectionMode = null;
	}

	@Override
	public SearchSubmitResult onSearchSubmit(String query) {
		PostsAdapter adapter = getAdapter();
		if (adapter.getItemCount() == 0) {
			return SearchSubmitResult.COLLAPSE;
		}
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		retainExtra.searchFoundPosts.clear();
		int listPosition = ListPosition.obtain(getRecyclerView()).position;
		retainExtra.searchLastPosition = 0;
		boolean positionDefined = false;
		Locale locale = Locale.getDefault();
		SearchHelper helper = new SearchHelper(Preferences.isAdvancedSearch());
		helper.setFlags("m", "r", "a", "d", "e", "n", "op");
		HashSet<String> queries = helper.handleQueries(locale, query);
		HashSet<String> fileNames = new HashSet<>();
		int newPostPosition = adapter.findPositionByPostNumber(parcelableExtra.newPostNumber);
		OUTER: for (int i = 0; i < adapter.getItemCount(); i++) {
			PostItem postItem = adapter.getItem(i);
			if (!postItem.isHidden(hidePerformer)) {
				String postNumber = postItem.getPostNumber();
				String comment = postItem.getComment().toString().toLowerCase(locale);
				int postPosition = getAdapter().findPositionByPostNumber(postNumber);
				boolean userPost = postItem.isUserPost();
				boolean reply = false;
				HashSet<String> referencesTo = postItem.getReferencesTo();
				if (referencesTo != null) {
					for (String referenceTo : referencesTo) {
						if (retainExtra.userPostNumbers.contains(referenceTo)) {
							reply = true;
							break;
						}
					}
				}
				boolean hasAttachments = postItem.hasAttachments();
				boolean deleted = postItem.isDeleted();
				boolean edited = lastEditedPostNumbers.contains(postNumber);
				boolean newPost = newPostPosition >= 0 && postPosition >= newPostPosition;
				boolean originalPoster = postItem.isOriginalPoster();
				if (!helper.checkFlags("m", userPost, "r", reply, "a", hasAttachments, "d", deleted, "e", edited,
						"n", newPost, "op", originalPoster)) {
					continue;
				}
				for (String lowQuery : helper.getExcluded()) {
					if (comment.contains(lowQuery)) {
						continue OUTER;
					}
				}
				String subject = postItem.getSubject().toLowerCase(locale);
				String name = postItem.getFullName().toString().toLowerCase(locale);
				fileNames.clear();
				List<AttachmentItem> attachmentItems = postItem.getAttachmentItems();
				if (attachmentItems != null) {
					for (AttachmentItem attachmentItem : attachmentItems) {
						String fileName = attachmentItem.getFileName();
						if (fileName != null) {
							fileNames.add(fileName.toLowerCase(locale));
							String originalName = attachmentItem.getOriginalName();
							if (originalName != null) {
								fileNames.add(originalName.toLowerCase(locale));
							}
						}
					}
				}
				boolean found = false;
				if (helper.hasIncluded()) {
					QUERIES: for (String lowQuery : helper.getIncluded()) {
						if (comment.contains(lowQuery)) {
							found = true;
							break;
						} else if (subject.contains(lowQuery)) {
							found = true;
							break;
						} else if (name.contains(lowQuery)) {
							found = true;
							break;
						} else {
							for (String fileName : fileNames) {
								if (fileName.contains(lowQuery)) {
									found = true;
									break QUERIES;
								}
							}
						}
					}
				} else {
					found = true;
				}
				if (found) {
					if (!positionDefined && i > listPosition) {
						retainExtra.searchLastPosition = retainExtra.searchFoundPosts.size();
						positionDefined = true;
					}
					retainExtra.searchFoundPosts.add(i);
				}
			}
		}
		boolean found = !retainExtra.searchFoundPosts.isEmpty();
		getAdapter().setHighlightText(found ? queries : Collections.emptyList());
		retainExtra.searching = true;
		if (found) {
			setCustomSearchView(searchController);
			retainExtra.searchLastPosition--;
			findForward();
			return SearchSubmitResult.ACCEPT;
		} else {
			setCustomSearchView(null);
			ToastUtils.show(getContext(), R.string.not_found);
			retainExtra.searchLastPosition = -1;
			updateSearchTitle();
			return SearchSubmitResult.DISCARD;
		}
	}

	@Override
	public void onSearchCancel() {
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		if (retainExtra.searching) {
			retainExtra.searching = false;
			setCustomSearchView(null);
			updateOptionsMenu();
			getAdapter().setHighlightText(Collections.emptyList());
		}
	}

	private void showSearchDialog() {
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		if (!retainExtra.searchFoundPosts.isEmpty()) {
			PostsAdapter adapter = getAdapter();
			HashSet<String> postNumbers = new HashSet<>();
			for (Integer position : retainExtra.searchFoundPosts) {
				PostItem postItem = adapter.getItem(position);
				postNumbers.add(postItem.getPostNumber());
			}
			getUiManager().dialog().displayList(adapter.getConfigurationSet(), postNumbers);
		}
	}

	private void findBack() {
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		int count = retainExtra.searchFoundPosts.size();
		if (count > 0) {
			retainExtra.searchLastPosition--;
			if (retainExtra.searchLastPosition < 0) {
				retainExtra.searchLastPosition += count;
			}
			ListViewUtils.smoothScrollToPosition(getRecyclerView(),
					retainExtra.searchFoundPosts.get(retainExtra.searchLastPosition));
			updateSearchTitle();
		}
	}

	private void findForward() {
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		int count = retainExtra.searchFoundPosts.size();
		if (count > 0) {
			retainExtra.searchLastPosition++;
			if (retainExtra.searchLastPosition >= count) {
				retainExtra.searchLastPosition -= count;
			}
			ListViewUtils.smoothScrollToPosition(getRecyclerView(),
					retainExtra.searchFoundPosts.get(retainExtra.searchLastPosition));
			updateSearchTitle();
		}
	}

	private void updateSearchTitle() {
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		searchTextResult.setText((retainExtra.searchLastPosition + 1) + "/" + retainExtra.searchFoundPosts.size());
	}

	private boolean handleNewPostDataList() {
		Page page = getPage();
		List<PostingService.NewPostData> newPostDataList = PostingService.getNewPostDataList(getContext(),
				page.chanName, page.boardName, page.threadNumber);
		if (newPostDataList != null) {
			boolean hasNewPostDataList = false;
			RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
			ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
			OUTER: for (PostingService.NewPostData newPostData : newPostDataList) {
				ReadPostsTask.UserPostPending userPostPending;
				if (newPostData.newThread) {
					userPostPending = new ReadPostsTask.NewThreadUserPostPending();
				} else if (newPostData.postNumber != null) {
					userPostPending = new ReadPostsTask.PostNumberUserPostPending(newPostData.postNumber);
					// Check this post had loaded before this callback was called
					// This can be unequivocally checked only for this type of UserPostPending
					for (PostItem postItem : getAdapter()) {
						if (userPostPending.isUserPost(postItem.getPost())) {
							postItem.setUserPost(true);
							retainExtra.userPostNumbers.add(postItem.getPostNumber());
							getUiManager().sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS);
							serializePosts();
							continue OUTER;
						}
					}
				} else {
					userPostPending = new ReadPostsTask.CommentUserPostPending(newPostData.comment);
				}
				parcelableExtra.userPostPendingList.add(userPostPending);
				hasNewPostDataList = true;
			}
			return hasNewPostDataList;
		}
		return false;
	}

	@Override
	public int onDrawerNumberEntered(int number) {
		PostsAdapter adapter = getAdapter();
		int count = adapter.getItemCount();
		boolean success = false;
		if (count > 0 && number > 0) {
			if (number <= count) {
				int position = adapter.findPositionByOrdinalIndex(number - 1);
				if (position >= 0) {
					ListViewUtils.smoothScrollToPosition(getRecyclerView(), position);
					success = true;
				}
			}
			if (!success) {
				int position = adapter.findPositionByPostNumber(Integer.toString(number));
				if (position >= 0) {
					ListViewUtils.smoothScrollToPosition(getRecyclerView(), position);
					success = true;
				} else {
					ToastUtils.show(getContext(), R.string.post_is_not_found);
				}
			}
		}
		int result = DrawerForm.RESULT_REMOVE_ERROR_MESSAGE;
		if (success) {
			result |= DrawerForm.RESULT_SUCCESS;
		}
		return result;
	}

	@Override
	public void updatePageConfiguration(String postNumber) {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		parcelableExtra.scrollToPostNumber = postNumber;
		if (readTask == null && deserializeTask == null) {
			if (!scrollToSpecifiedPost(false)) {
				refreshPosts(true, false);
			}
		}
	}

	@Override
	public void onListPulled(PullableWrapper wrapper, PullableWrapper.Side side) {
		refreshPosts(true, false, true);
	}

	private boolean scrollToSpecifiedPost(boolean instantly) {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (parcelableExtra.scrollToPostNumber != null) {
			int position = getAdapter().findPositionByPostNumber(parcelableExtra.scrollToPostNumber);
			if (position >= 0) {
				if (instantly) {
					((LinearLayoutManager) getRecyclerView().getLayoutManager())
							.scrollToPositionWithOffset(position, 0);
				} else {
					ListViewUtils.smoothScrollToPosition(getRecyclerView(), position);
				}
				parcelableExtra.scrollToPostNumber = null;
			}
		}
		return parcelableExtra.scrollToPostNumber == null;
	}

	private void onFirstPostsLoad() {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (parcelableExtra.scrollToPostNumber == null) {
			restoreListPosition();
		}
	}

	private void onAfterPostsLoad(boolean fromCache) {
		Page page = getPage();
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (!parcelableExtra.isAddedToHistory) {
			parcelableExtra.isAddedToHistory = true;
			HistoryDatabase.getInstance().addHistory(page.chanName, page.boardName,
					page.threadNumber, parcelableExtra.threadTitle);
		}
		if (retainExtra.cachedPosts != null) {
			Pair<String, Uri> originalThreadData = null;
			Uri archivedThreadUri = retainExtra.cachedPosts.getArchivedThreadUri();
			if (archivedThreadUri != null) {
				String chanName = ChanManager.getInstance().getChanNameByHost(archivedThreadUri.getAuthority());
				if (chanName != null) {
					originalThreadData = new Pair<>(chanName, archivedThreadUri);
				}
			}
			if ((this.originalThreadData == null) != (originalThreadData == null)) {
				this.originalThreadData = originalThreadData;
				updateOptionsMenu();
			}
		}
		if (!fromCache) {
			FavoritesStorage.getInstance().modifyPostsCount(page.chanName, page.boardName,
					page.threadNumber, getAdapter().getExistingPostsCount());
		}
		Iterator<PostItem> iterator = getAdapter().iterator();
		if (iterator.hasNext()) {
			String title = iterator.next().getSubjectOrComment();
			if (StringUtils.isEmptyOrWhitespace(title)) {
				title = null;
			}
			FavoritesStorage.getInstance().modifyTitle(page.chanName, page.boardName,
					page.threadNumber, title, false);
			if (!StringUtils.equals(StringUtils.nullIfEmpty(parcelableExtra.threadTitle), title)) {
				HistoryDatabase.getInstance().refreshTitles(page.chanName, page.boardName,
						page.threadNumber, title);
				parcelableExtra.threadTitle = title;
				notifyTitleChanged();
			}
		}
	}

	private static final Handler HANDLER = new Handler();

	private final Runnable refreshRunnable = () -> {
		if (deserializeTask == null && readTask == null) {
			refreshPosts(true, false);
		}
		queueNextRefresh(false);
	};

	private void queueNextRefresh(boolean instant) {
		HANDLER.removeCallbacks(refreshRunnable);
		int mode = Preferences.getAutoRefreshMode();
		boolean enabled = mode == Preferences.AUTO_REFRESH_MODE_SEPARATE && autoRefreshEnabled ||
				mode == Preferences.AUTO_REFRESH_MODE_ENABLED;
		if (enabled) {
			int interval = mode == Preferences.AUTO_REFRESH_MODE_SEPARATE ? autoRefreshInterval
					: Preferences.getAutoRefreshInterval();
			if (instant) {
				HANDLER.post(refreshRunnable);
			} else {
				HANDLER.postDelayed(refreshRunnable, interval * 1000);
			}
		}
	}

	private void stopRefresh() {
		HANDLER.removeCallbacks(refreshRunnable);
	}

	private void refreshPosts(boolean checkModified, boolean reload) {
		refreshPosts(checkModified, reload, getAdapter().getItemCount() > 0);
	}

	private void refreshPosts(boolean checkModified, boolean reload, boolean showPull) {
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (deserializeTask != null) {
			parcelableExtra.queuedRefresh = QueuedRefresh.max(parcelableExtra.queuedRefresh,
					reload ? QueuedRefresh.RELOAD : QueuedRefresh.REFRESH);
			return;
		}
		parcelableExtra.queuedRefresh = QueuedRefresh.NONE;
		if (readTask != null) {
			readTask.cancel();
		}
		Page page = getPage();
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		PostsAdapter adapter = getAdapter();
		boolean partialLoading = adapter.getItemCount() > 0;
		boolean useValidator = checkModified && partialLoading && !reload;
		readTask = new ReadPostsTask(this, page.chanName, page.boardName, page.threadNumber,
				retainExtra.cachedPosts, useValidator, reload, adapter.getLastPostNumber(),
				parcelableExtra.userPostPendingList);
		readTask.executeOnExecutor(ReadPostsTask.THREAD_POOL_EXECUTOR);
		if (showPull) {
			getRecyclerView().getWrapper().startBusyState(PullableWrapper.Side.BOTTOM);
			switchView(ViewType.LIST, null);
		} else {
			getRecyclerView().getWrapper().startBusyState(PullableWrapper.Side.BOTH);
			switchView(ViewType.PROGRESS, null);
		}
	}

	@Override
	public void onRequestPreloadPosts(ArrayList<ReadPostsTask.Patch> patches, int oldCount) {
		int threshold = ListViewUtils.getScrollJumpThreshold(getContext());
		ArrayList<PostItem> postItems = oldCount == 0 ? new ArrayList<>() : ConcurrentUtils.mainGet(() -> {
			ArrayList<PostItem> buildPostItems = new ArrayList<>();
			PostsAdapter adapter = getAdapter();
			int count = adapter.getItemCount();
			int handleOldCount = Math.min(threshold, count);
			for (int i = 0; i < handleOldCount; i++) {
				PostItem postItem = adapter.getItem(count - i - 1);
				buildPostItems.add(postItem);
			}
			return buildPostItems;
		});
		int handleNewCount = Math.min(threshold / 4, patches.size());
		int i = 0;
		for (ReadPostsTask.Patch patch : patches) {
			if (!patch.replaceAtIndex && patch.index >= oldCount) {
				postItems.add(patch.postItem);
				if (++i == handleNewCount) {
					break;
				}
			}
		}
		CountDownLatch latch = new CountDownLatch(1);
		getAdapter().preloadPosts(postItems, latch::countDown);
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException e) {
				// Uninterruptible wait, ignore exception
			}
		}
	}

	@Override
	public void onDeserializePostsComplete(boolean success, Posts posts, ArrayList<PostItem> postItems) {
		deserializeTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		switchView(ViewType.LIST, null);
		if (success && postItems != null) {
			RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
			retainExtra.userPostNumbers.clear();
			for (PostItem postItem : postItems) {
				if (postItem.isUserPost()) {
					retainExtra.userPostNumbers.add(postItem.getPostNumber());
				}
			}
		}
		onDeserializePostsCompleteInternal(success, posts, postItems, false);
	}

	private void onDeserializePostsCompleteInternal(boolean success, Posts posts,
			ArrayList<PostItem> postItems, boolean fromState) {
		PostsAdapter adapter = getAdapter();
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		retainExtra.cachedPosts = null;
		retainExtra.cachedPostItems.clear();

		if (success) {
			hidePerformer.decodeLocalAutohide(posts);
			retainExtra.cachedPosts = posts;
			retainExtra.cachedPostItems.addAll(postItems);
			ArrayList<ReadPostsTask.Patch> patches = new ArrayList<>();
			for (int i = 0; i < postItems.size(); i++) {
				patches.add(new ReadPostsTask.Patch(postItems.get(i), i));
			}
			adapter.setItems(patches, fromState);
			for (PostItem postItem : adapter) {
				if (parcelableExtra.expandedPosts.contains(postItem.getPostNumber())) {
					postItem.setExpanded(true);
				}
			}
			Pair<Boolean, Integer> autoRefreshData = posts.getAutoRefreshData();
			autoRefreshEnabled = autoRefreshData.first;
			autoRefreshInterval = Math.min(Math.max(autoRefreshData.second, Preferences.MIN_AUTO_REFRESH_INTERVAL),
					Preferences.MAX_AUTO_REFRESH_INTERVAL);
			onFirstPostsLoad();
			onAfterPostsLoad(true);
			if (!fromState) {
				showScaleAnimation();
			}
			scrollToSpecifiedPost(true);
			if (parcelableExtra.queuedRefresh != QueuedRefresh.NONE || parcelableExtra.hasNewPostDataList) {
				boolean reload = parcelableExtra.queuedRefresh == QueuedRefresh.RELOAD;
				refreshPosts(true, reload);
			}
			queueNextRefresh(false);
		} else {
			refreshPosts(false, false);
		}
		updateOptionsMenu();

		if (parcelableExtra.selectedItems != null) {
			Set<String> selected = parcelableExtra.selectedItems;
			parcelableExtra.selectedItems = null;
			if (success) {
				for (String postNumber : selected) {
					PostItem postItem = adapter.findPostItem(postNumber);
					adapter.toggleItemSelected(postItem);
				}
				selectionMode = startActionMode(this);
			}
		}
	}

	@Override
	public void onReadPostsSuccess(ReadPostsTask.Result result, boolean fullThread,
			ArrayList<ReadPostsTask.UserPostPending> removedUserPostPendings) {
		readTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		switchView(ViewType.LIST, null);
		PostsAdapter adapter = getAdapter();
		Page page = getPage();
		if (adapter.getItemCount() == 0) {
			StatisticsStorage.getInstance().incrementThreadsViewed(page.chanName);
		}
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		parcelableExtra.hasNewPostDataList = false;
		boolean wasEmpty = adapter.getItemCount() == 0;
		final int newPostPosition = adapter.getItemCount();
		if (removedUserPostPendings != null) {
			parcelableExtra.userPostPendingList.removeAll(removedUserPostPendings);
		}
		if (fullThread) {
			// Thread was opened for the first time
			retainExtra.cachedPosts = result.posts;
			retainExtra.cachedPostItems.clear();
			retainExtra.userPostNumbers.clear();
			for (ReadPostsTask.Patch patch : result.patches) {
				retainExtra.cachedPostItems.add(patch.postItem);
				if (patch.newPost.isUserPost()) {
					retainExtra.userPostNumbers.add(patch.newPost.getPostNumber());
				}
			}
			adapter.setItems(result.patches, false);
			boolean allowCache = CacheManager.getInstance().allowPagesCache(page.chanName);
			if (allowCache) {
				for (PostItem postItem : retainExtra.cachedPostItems) {
					postItem.setUnread(true);
				}
			}
			onFirstPostsLoad();
		} else {
			if (retainExtra.cachedPosts != null) {
				// Copy data from old model to new model
				Pair<Boolean, Integer> autoRefreshData = retainExtra.cachedPosts.getAutoRefreshData();
				result.posts.setAutoRefreshData(autoRefreshData.first, autoRefreshData.second);
				result.posts.setLocalAutohide(retainExtra.cachedPosts.getLocalAutohide());
			}
			retainExtra.cachedPosts = result.posts;
			int repliesCount = 0;
			if (!result.patches.isEmpty()) {
				// Copy data from old model to new model
				for (ReadPostsTask.Patch patch : result.patches) {
					if (patch.oldPost != null) {
						if (patch.oldPost.isUserPost()) {
							patch.newPost.setUserPost(true);
						}
						if (patch.oldPost.isHidden()) {
							patch.newPost.setHidden(true);
						}
						if (patch.oldPost.isShown()) {
							patch.newPost.setHidden(false);
						}
					}
				}
				for (ReadPostsTask.Patch patch : result.patches) {
					if (patch.newPost.isUserPost()) {
						retainExtra.userPostNumbers.add(patch.newPost.getPostNumber());
					}
					if (patch.newPostAddedToEnd) {
						HashSet<String> referencesTo = patch.postItem.getReferencesTo();
						if (referencesTo != null) {
							for (String postNumber : referencesTo) {
								if (retainExtra.userPostNumbers.contains(postNumber)) {
									repliesCount++;
									break;
								}
							}
						}
					}
				}
				adapter.mergeItems(result.patches);
				retainExtra.cachedPostItems.clear();
				for (PostItem postItem : adapter) {
					retainExtra.cachedPostItems.add(postItem);
				}
				// Mark changed posts as unread
				for (ReadPostsTask.Patch patch : result.patches) {
					patch.postItem.setUnread(true);
				}
			}
			if (result.newCount > 0 || repliesCount > 0 || result.deletedCount > 0 || result.hasEdited) {
				String message;
				if (repliesCount > 0 || result.deletedCount > 0) {
					message = getQuantityString(R.plurals.number_new__format, result.newCount, result.newCount);
					if (repliesCount > 0) {
						message = getString(R.string.__enumeration_format, message,
								getQuantityString(R.plurals.number_replies__format, repliesCount, repliesCount));
					}
					if (result.deletedCount > 0) {
						message = getString(R.string.__enumeration_format, message,
								getQuantityString(R.plurals.number_deleted__format,
										result.deletedCount, result.deletedCount));
					}
				} else if (result.newCount > 0) {
					message = getQuantityString(R.plurals.number_new_posts__format,
							result.newCount, result.newCount);
				} else {
					message = getString(R.string.some_posts_have_been_edited);
				}
				if (result.newCount > 0 && newPostPosition < adapter.getItemCount()) {
					PostItem newPostItem = adapter.getItem(newPostPosition);
					getParcelableExtra(ParcelableExtra.FACTORY).newPostNumber = newPostItem.getPostNumber();
					ClickableToast.show(getContext(), message, getString(R.string.show), true, () -> {
						if (!isDestroyed()) {
							String newPostNumber = getParcelableExtra(ParcelableExtra.FACTORY).newPostNumber;
							int newPostIndex = getAdapter().findPositionByPostNumber(newPostNumber);
							if (newPostIndex >= 0) {
								ListViewUtils.smoothScrollToPosition(getRecyclerView(), newPostIndex);
							}
						}
					});
				} else {
					ClickableToast.show(getContext(), message);
				}
			}
		}
		boolean updateAdapters = result.newCount > 0 || result.deletedCount > 0 || result.hasEdited;
		serializePosts();
		if (result.hasEdited) {
			lastEditedPostNumbers.clear();
			for (ReadPostsTask.Patch patch : result.patches) {
				if (!patch.newPostAddedToEnd) {
					lastEditedPostNumbers.add(patch.newPost.getPostNumber());
				}
			}
		}
		if (updateAdapters) {
			getUiManager().dialog().updateAdapters(adapter.getConfigurationSet().stackInstance);
			notifyAllAdaptersChanged();
		}
		onAfterPostsLoad(false);
		if (wasEmpty && adapter.getItemCount() > 0) {
			showScaleAnimation();
		}
		scrollToSpecifiedPost(wasEmpty);
		parcelableExtra.scrollToPostNumber = null;
		updateOptionsMenu();
	}

	@Override
	public void onReadPostsEmpty() {
		readTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		switchView(ViewType.LIST, null);
		if (getAdapter().getItemCount() == 0) {
			displayDownloadError(true, getString(R.string.empty_response));
		} else {
			onAfterPostsLoad(false);
		}
	}

	@Override
	public void onReadPostsRedirect(RedirectException.Target target) {
		readTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		handleRedirect(target.chanName, target.boardName, target.threadNumber, target.postNumber);
	}

	@Override
	public void onReadPostsFail(ErrorItem errorItem) {
		readTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		displayDownloadError(true, errorItem.toString());
		ParcelableExtra parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY);
		parcelableExtra.scrollToPostNumber = null;
	}

	private void displayDownloadError(boolean show, String message) {
		if (show && getAdapter().getItemCount() > 0) {
			ClickableToast.show(getContext(), message);
			return;
		}
		switchView(ViewType.ERROR, message);
	}

	private Runnable postNotifyDataSetChanged;

	@Override
	public void onPostItemMessage(PostItem postItem, UiManager.Message message) {
		int position = getAdapter().positionOf(postItem);
		if (position == -1) {
			return;
		}
		switch (message) {
			case POST_INVALIDATE_ALL_VIEWS: {
				if (postNotifyDataSetChanged == null) {
					postNotifyDataSetChanged = getAdapter()::notifyDataSetChanged;
				}
				RecyclerView recyclerView = getRecyclerView();
				recyclerView.removeCallbacks(postNotifyDataSetChanged);
				recyclerView.post(postNotifyDataSetChanged);
				break;
			}
			case INVALIDATE_COMMENT_VIEW: {
				getAdapter().invalidateComment(position);
				break;
			}
			case PERFORM_SWITCH_USER_MARK: {
				postItem.setUserPost(!postItem.isUserPost());
				RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
				if (postItem.isUserPost()) {
					retainExtra.userPostNumbers.add(postItem.getPostNumber());
				} else {
					retainExtra.userPostNumbers.remove(postItem.getPostNumber());
				}
				getUiManager().sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS);
				serializePosts();
				break;
			}
			case PERFORM_SWITCH_HIDE: {
				postItem.setHidden(!postItem.isHidden(hidePerformer));
				getUiManager().sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS);
				serializePosts();
				break;
			}
			case PERFORM_HIDE_REPLIES:
			case PERFORM_HIDE_NAME:
			case PERFORM_HIDE_SIMILAR: {
				PostsAdapter adapter = getAdapter();
				adapter.cancelPreloading();
				HidePerformer.AddResult result;
				switch (message) {
					case PERFORM_HIDE_REPLIES: {
						result = hidePerformer.addHideByReplies(postItem);
						break;
					}
					case PERFORM_HIDE_NAME: {
						result = hidePerformer.addHideByName(getContext(), postItem);
						break;
					}
					case PERFORM_HIDE_SIMILAR: {
						result = hidePerformer.addHideSimilar(getContext(), postItem);
						break;
					}
					default: {
						throw new RuntimeException();
					}
				}
				if (result == HidePerformer.AddResult.SUCCESS) {
					postItem.resetHidden();
					adapter.invalidateHidden();
					notifyAllAdaptersChanged();
					hidePerformer.encodeLocalAutohide(getRetainExtra(RetainExtra.FACTORY).cachedPosts);
					serializePosts();
				} else if (result == HidePerformer.AddResult.EXISTS && !postItem.isHiddenUnchecked()) {
					postItem.resetHidden();
					notifyAllAdaptersChanged();
					serializePosts();
				}
				adapter.preloadPosts(((LinearLayoutManager) getRecyclerView().getLayoutManager())
						.findFirstVisibleItemPosition());
				break;
			}
			case PERFORM_GO_TO_POST: {
				PullableRecyclerView recyclerView = getRecyclerView();
				// Avoid concurrent modification
				recyclerView.post(() -> getUiManager().dialog()
						.closeDialogs(getAdapter().getConfigurationSet().stackInstance));
				ListViewUtils.smoothScrollToPosition(recyclerView, position);
				break;
			}
		}
	}

	private void serializePosts() {
		Page page = getPage();
		CacheManager.getInstance().serializePosts(page.chanName, page.boardName,
				page.threadNumber, getRetainExtra(RetainExtra.FACTORY).cachedPosts);
	}
}

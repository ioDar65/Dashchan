package com.mishiranu.dashchan.ui.navigator.page;

import android.net.Uri;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import chan.content.ChanPerformer;
import chan.content.model.ThreadSummary;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.async.ReadThreadSummariesTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.adapter.ArchiveAdapter;
import com.mishiranu.dashchan.util.DialogMenu;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.PullableRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;

public class ArchivePage extends ListPage implements ArchiveAdapter.Callback,
		ReadThreadSummariesTask.Callback {
	private static class RetainExtra {
		public static final ExtraFactory<RetainExtra> FACTORY = RetainExtra::new;

		public ThreadSummary[] threadSummaries;
		public int pageNumber;
	}

	private ReadThreadSummariesTask readTask;
	private boolean showScaleOnSuccess;

	private ArchiveAdapter getAdapter() {
		return (ArchiveAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		PullableRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
		if (!C.API_LOLLIPOP) {
			float density = ResourceUtils.obtainDensity(recyclerView);
			ViewUtils.setNewPadding(recyclerView, (int) (16f * density), null, (int) (16f * density), null);
		}
		ArchiveAdapter adapter = new ArchiveAdapter(this);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				adapter::configureDivider));
		recyclerView.getWrapper().setPullSides(PullableWrapper.Side.BOTH);
		adapter.applyFilter(getInitSearch().currentQuery);
		RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
		if (retainExtra.threadSummaries != null) {
			adapter.setItems(retainExtra.threadSummaries);
			restoreListPosition();
		} else {
			showScaleOnSuccess = true;
			refreshThreads(false, false);
		}
	}

	@Override
	protected void onDestroy() {
		if (readTask != null) {
			readTask.cancel();
			readTask = null;
		}
	}

	@Override
	public String obtainTitle() {
		Page page = getPage();
		return getString(R.string.archive) + ": " +
				StringUtils.formatBoardTitle(page.chanName, page.boardName, null);
	}

	@Override
	public void onItemClick(String threadNumber) {
		if (threadNumber != null) {
			Page page = getPage();
			getUiManager().navigator().navigatePosts(page.chanName, page.boardName, threadNumber, null, null, 0);
		}
	}

	@Override
	public boolean onItemLongClick(String threadNumber) {
		Page page = getPage();
		DialogMenu dialogMenu = new DialogMenu(getContext());
		dialogMenu.add(R.string.copy_link, () -> {
			Uri uri = getChanLocator().safe(true).createThreadUri(page.boardName, threadNumber);
			if (uri != null) {
				StringUtils.copyToClipboard(getContext(), uri.toString());
			}
		});
		if (!FavoritesStorage.getInstance().hasFavorite(page.chanName, page.boardName, threadNumber)) {
			dialogMenu.add(R.string.add_to_favorites, () -> FavoritesStorage.getInstance()
					.add(page.chanName, page.boardName, threadNumber, null, 0));
		}
		dialogMenu.show(getUiManager().getConfigurationLock());
		return true;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_search, 0, R.string.filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_refresh: {
				refreshThreads(!getAdapter().isRealEmpty(), false);
				return true;
			}
		}
		return false;
	}

	@Override
	public void onSearchQueryChange(String query) {
		getAdapter().applyFilter(query);
	}

	@Override
	public void onListPulled(PullableWrapper wrapper, PullableWrapper.Side side) {
		refreshThreads(true, side == PullableWrapper.Side.BOTTOM);
	}

	private void refreshThreads(boolean showPull, boolean nextPage) {
		if (readTask != null) {
			readTask.cancel();
		}
		Page page = getPage();
		int pageNumber = 0;
		if (nextPage) {
			RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
			if (retainExtra.threadSummaries != null) {
				pageNumber = retainExtra.pageNumber + 1;
			}
		}
		readTask = new ReadThreadSummariesTask(page.chanName, page.boardName, pageNumber,
				ChanPerformer.ReadThreadSummariesData.TYPE_ARCHIVED_THREADS, this);
		readTask.executeOnExecutor(ReadThreadSummariesTask.THREAD_POOL_EXECUTOR);
		if (showPull) {
			getRecyclerView().getWrapper().startBusyState(PullableWrapper.Side.TOP);
			switchView(ViewType.LIST, null);
		} else {
			getRecyclerView().getWrapper().startBusyState(PullableWrapper.Side.BOTH);
			switchView(ViewType.PROGRESS, null);
		}
	}

	@Override
	public void onReadThreadSummariesSuccess(ThreadSummary[] threadSummaries, int pageNumber) {
		readTask = null;
		PullableRecyclerView recyclerView = getRecyclerView();
		recyclerView.getWrapper().cancelBusyState();
		boolean showScale = showScaleOnSuccess;
		showScaleOnSuccess = false;
		if (pageNumber == 0 && threadSummaries == null) {
			if (getAdapter().isRealEmpty()) {
				switchView(ViewType.ERROR, R.string.empty_response);
			} else {
				ClickableToast.show(getContext(), R.string.empty_response);
			}
		} else {
			switchView(ViewType.LIST, null);
			RetainExtra retainExtra = getRetainExtra(RetainExtra.FACTORY);
			if (pageNumber == 0) {
				getAdapter().setItems(threadSummaries);
				retainExtra.threadSummaries = threadSummaries;
				retainExtra.pageNumber = 0;
				ListViewUtils.cancelListFling(recyclerView);
				recyclerView.scrollToPosition(0);
				if (showScale) {
					showScaleAnimation();
				}
			} else {
				threadSummaries = ReadThreadSummariesTask.concatenate(retainExtra.threadSummaries, threadSummaries);
				int oldCount = retainExtra.threadSummaries.length;
				if (threadSummaries.length > oldCount) {
					boolean needScroll = false;
					int childCount = recyclerView.getChildCount();
					if (childCount > 0) {
						View child = recyclerView.getChildAt(childCount - 1);
						int position = recyclerView.getChildViewHolder(child).getAdapterPosition();
						needScroll = position + 1 == oldCount &&
								recyclerView.getHeight() - recyclerView.getPaddingBottom() - child.getBottom() >= 0;
					}
					getAdapter().setItems(threadSummaries);
					retainExtra.threadSummaries = threadSummaries;
					retainExtra.pageNumber = pageNumber;
					if (needScroll) {
						ListViewUtils.smoothScrollToPosition(recyclerView, oldCount);
					}
				}
			}
		}
	}

	@Override
	public void onReadThreadSummariesFail(ErrorItem errorItem) {
		readTask = null;
		getRecyclerView().getWrapper().cancelBusyState();
		if (getAdapter().isRealEmpty()) {
			switchView(ViewType.ERROR, errorItem.toString());
		} else {
			ClickableToast.show(getContext(), errorItem.toString());
		}
	}
}

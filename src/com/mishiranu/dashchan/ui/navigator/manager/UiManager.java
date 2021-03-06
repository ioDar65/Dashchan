package com.mishiranu.dashchan.ui.navigator.manager;

import android.content.Context;
import android.view.View;
import chan.content.ChanLocator;
import com.mishiranu.dashchan.content.model.AttachmentItem;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay;
import com.mishiranu.dashchan.ui.posting.Replyable;
import com.mishiranu.dashchan.util.ConfigurationLock;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.WeakObservable;
import com.mishiranu.dashchan.widget.CommentTextView;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

public class UiManager {
	private final Context context;
	private final ViewUnit viewUnit;
	private final DialogUnit dialogUnit;
	private final InteractionUnit interactionUnit;
	private final WeakObservable<Observer> observable = new WeakObservable<>();

	private final LocalNavigator localNavigator;
	private final DownloadProvider downloadProvider;
	private final ConfigurationLock configurationLock;

	public UiManager(Context context, LocalNavigator localNavigator,
			DownloadProvider downloadProvider, ConfigurationLock configurationLock) {
		this.context = context;
		viewUnit = new ViewUnit(this);
		dialogUnit = new DialogUnit(this);
		interactionUnit = new InteractionUnit(this);
		this.localNavigator = localNavigator;
		this.downloadProvider = downloadProvider;
		this.configurationLock = configurationLock;
	}

	Context getContext() {
		return context;
	}

	public ConfigurationLock getConfigurationLock() {
		return configurationLock;
	}

	public ViewUnit view() {
		return viewUnit;
	}

	public DialogUnit dialog() {
		return dialogUnit;
	}

	public InteractionUnit interaction() {
		return interactionUnit;
	}

	public LocalNavigator navigator() {
		return localNavigator;
	}

	public void download(DownloadCallback callback) {
		DownloadService.Binder binder = downloadProvider.getBinder();
		if (binder != null) {
			callback.callback(binder);
		}
	}

	public enum Message {
		POST_INVALIDATE_ALL_VIEWS,
		INVALIDATE_COMMENT_VIEW,
		PERFORM_SWITCH_USER_MARK,
		PERFORM_SWITCH_HIDE,
		PERFORM_HIDE_REPLIES,
		PERFORM_HIDE_NAME,
		PERFORM_HIDE_SIMILAR,
		PERFORM_GO_TO_POST
	}

	public interface Observer {
		public void onPostItemMessage(PostItem postItem, Message message);
	}

	public void sendPostItemMessage(View view, Message message) {
		Holder holder = ListViewUtils.getViewHolder(view, Holder.class);
		sendPostItemMessage(holder.getPostItem(), message);
	}

	public void sendPostItemMessage(PostItem postItem, Message message) {
		for (Observer observer : observable) {
			observer.onPostItemMessage(postItem, message);
		}
	}

	public WeakObservable<Observer> observable() {
		return observable;
	}

	public interface PostsProvider extends Iterable<PostItem> {
		PostItem findPostItem(String postNumber);
	}

	public interface DownloadProvider {
		DownloadService.Binder getBinder();
	}

	public interface DownloadCallback {
		void callback(DownloadService.Binder binder);
	}

	public interface LocalNavigator {
		void navigateBoardsOrThreads(String chanName, String boardName, int flags);
		void navigatePosts(String chanName, String boardName, String threadNumber, String postNumber,
				String threadTitle, int flags);
		void navigateSearch(String chanName, String boardName, String searchQuery, int flags);
		void navigateArchive(String chanName, String boardName, int flags);
		void navigateTarget(String chanName, ChanLocator.NavigationData data, int flags);
		void navigatePosting(String chanName, String boardName, String threadNumber,
				Replyable.ReplyData... data);
		void navigateGallery(String chanName, GalleryItem.GallerySet gallerySet, int imageIndex,
				View view, GalleryOverlay.NavigatePostMode navigatePostMode, boolean galleryMode);
	}

	public enum Selection {DISABLED, NOT_SELECTED, SELECTED, THREADSHOT}

	public static class DemandSet {
		public boolean lastInList = false;
		public Selection selection = Selection.DISABLED;
		public boolean showOpenThreadButton = false;
		public Collection<String> highlightText = Collections.emptyList();
	}

	public static class ConfigurationSet {
		public final Replyable replyable;
		public final PostsProvider postsProvider;
		public final HidePerformer hidePerformer;
		public final GalleryItem.GallerySet gallerySet;
		public final DialogUnit.StackInstance stackInstance;
		public final CommentTextView.LinkListener linkListener;
		public final HashSet<String> userPostNumbers;

		public final boolean mayCollapse;
		public final boolean isDialog;
		public final boolean allowMyMarkEdit;
		public final boolean allowHiding;
		public final boolean allowGoToPost;
		public final String repliesToPost;

		public ConfigurationSet(Replyable replyable, PostsProvider postsProvider, HidePerformer hidePerformer,
				GalleryItem.GallerySet gallerySet, DialogUnit.StackInstance stackInstance,
				CommentTextView.LinkListener linkListener, HashSet<String> userPostNumbers,
				boolean mayCollapse, boolean isDialog, boolean allowMyMarkEdit,
				boolean allowHiding, boolean allowGoToPost, String repliesToPost) {
			this.replyable = replyable;
			this.postsProvider = postsProvider;
			this.hidePerformer = hidePerformer;
			this.gallerySet = gallerySet;
			this.stackInstance = stackInstance;
			this.linkListener = linkListener;
			this.userPostNumbers = userPostNumbers;

			this.mayCollapse = mayCollapse;
			this.isDialog = isDialog;
			this.allowMyMarkEdit = allowMyMarkEdit;
			this.allowHiding = allowHiding;
			this.allowGoToPost = allowGoToPost;
			this.repliesToPost = repliesToPost;
		}

		public ConfigurationSet copy(boolean mayCollapse, boolean isDialog, String repliesToPost) {
			return new ConfigurationSet(replyable, postsProvider, hidePerformer,
					gallerySet, stackInstance, linkListener, userPostNumbers, mayCollapse, isDialog,
					allowMyMarkEdit, allowHiding, allowGoToPost, repliesToPost);
		}
	}

	public interface ThumbnailClickListener extends View.OnClickListener {
		public void update(int index, boolean mayShowDialog, GalleryOverlay.NavigatePostMode navigatePostMode);
	}

	public interface ThumbnailLongClickListener extends View.OnLongClickListener {
		public void update(AttachmentItem attachmentItem);
	}

	public interface Holder {
		PostItem getPostItem();
		ConfigurationSet getConfigurationSet();
		GalleryItem.GallerySet getGallerySet();
	}

	public void onFinish() {
		dialogUnit.onFinish();
	}
}

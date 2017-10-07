package org.wordpress.android.ui.themes;

import android.app.Fragment;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.android.volley.toolbox.ImageLoader.ImageListener;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.model.ThemeModel;
import org.wordpress.android.fluxc.store.ThemeStore;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper.RefreshListener;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;
import org.wordpress.android.widgets.HeaderGridView;
import org.wordpress.android.widgets.WPNetworkImageView;

import javax.inject.Inject;

import static org.wordpress.android.util.WPSwipeToRefreshHelper.buildSwipeToRefreshHelper;

/**
 * A fragment display the themes on a grid view.
 */
public class ThemeBrowserFragment extends Fragment implements RecyclerListener, AdapterView.OnItemSelectedListener,
        AbsListView.OnScrollListener {
    public static ThemeBrowserFragment newInstance(SiteModel site) {
        ThemeBrowserFragment fragment = new ThemeBrowserFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable(WordPress.SITE, site);
        fragment.setArguments(bundle);
        return fragment;
    }

    public interface ThemeBrowserFragmentCallback {
        void onActivateSelected(String themeId);
        void onTryAndCustomizeSelected(String themeId);
        void onViewSelected(String themeId);
        void onDetailsSelected(String themeId);
        void onSupportSelected(String themeId);
        void onSearchClicked();
    }

    protected static final String BUNDLE_PAGE = "BUNDLE_PAGE";
    protected static final int THEME_FILTER_ALL_INDEX = 0;
    protected static final int THEME_FILTER_FREE_INDEX = 1;
    protected static final int THEME_FILTER_PREMIUM_INDEX = 2;

    protected SwipeToRefreshHelper mSwipeToRefreshHelper;
    protected ThemeBrowserActivity mThemeBrowserActivity;
    private String mCurrentThemeId;
    private HeaderGridView mGridView;
    private RelativeLayout mEmptyView;
    private TextView mNoResultText;
    private TextView mCurrentThemeTextView;
    private ThemeBrowserAdapter mAdapter;
    private Spinner mSpinner;
    private ThemeBrowserFragmentCallback mCallback;
    private int mPage = 1;
    private boolean mShouldRefreshOnStart;
    private TextView mEmptyTextView;
    private ProgressBar mProgressBar;
    private SiteModel mSite;

    @Inject ThemeStore mThemeStore;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        if (savedInstanceState == null) {
            mSite = (SiteModel) getArguments().getSerializable(WordPress.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }

        if (mSite == null) {
            ToastUtils.showToast(getActivity(), R.string.blog_not_found, ToastUtils.Duration.SHORT);
            getActivity().finish();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        try {
            mCallback = (ThemeBrowserFragmentCallback) context;
            mThemeBrowserActivity = (ThemeBrowserActivity) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement ThemeBrowserFragmentCallback");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallback = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.theme_browser_fragment, container, false);

        setRetainInstance(true);
        mNoResultText = (TextView) view.findViewById(R.id.theme_no_search_result_text);
        mEmptyTextView = (TextView) view.findViewById(R.id.text_empty);
        mEmptyView = (RelativeLayout) view.findViewById(R.id.empty_view);
        mProgressBar = (ProgressBar) view.findViewById(R.id.theme_loading_progress_bar);

        configureGridView(inflater, view);
        configureSwipeToRefresh(view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (this instanceof ThemeSearchFragment) {
            mThemeBrowserActivity.setThemeSearchFragment((ThemeSearchFragment) this);
        } else {
            mThemeBrowserActivity.setThemeBrowserFragment(this);
        }
        Cursor cursor = fetchThemes(getSpinnerPosition());

        if (cursor == null) {
            return;
        }

        mAdapter = new ThemeBrowserAdapter(mThemeBrowserActivity, cursor, false, mCallback);
        setEmptyViewVisible(mAdapter.getCount() == 0);
        mGridView.setAdapter(mAdapter);
        restoreState(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();

        mThemeBrowserActivity.fetchCurrentTheme();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mGridView != null) {
            outState.putInt(BUNDLE_PAGE, mPage);
        }
        outState.putSerializable(WordPress.SITE, mSite);
    }

    public TextView getEmptyTextView() {
        return mEmptyTextView;
    }

    public TextView getCurrentThemeTextView() {
        return mCurrentThemeTextView;
    }

    public void setCurrentThemeId(String currentThemeId) {
        mCurrentThemeId = currentThemeId;
    }

    public int getPage() {
        return mPage;
    }

    protected void addHeaderViews(LayoutInflater inflater) {
        addMainHeader(inflater);
        configureAndAddSearchHeader(inflater);
    }

    protected void configureSwipeToRefresh(View view) {
        mSwipeToRefreshHelper = buildSwipeToRefreshHelper(
                (CustomSwipeRefreshLayout) view.findViewById(R.id.ptr_layout),
                new RefreshListener() {
                    @Override
                    public void onRefreshStarted() {
                        if (!isAdded()) {
                            return;
                        }
                        if (!NetworkUtils.checkConnection(mThemeBrowserActivity)) {
                            mSwipeToRefreshHelper.setRefreshing(false);
                            mEmptyTextView.setText(R.string.no_network_title);
                            return;
                        }
                        setRefreshing(true);
                        mThemeBrowserActivity.fetchInstalledThemesIfJetpackSite();
                        mThemeBrowserActivity.fetchWpComThemesIfSyncTimedOut(true);
                    }
                }
        );
        mSwipeToRefreshHelper.setRefreshing(mShouldRefreshOnStart);
    }

    private void configureGridView(LayoutInflater inflater, View view) {
        mGridView = (HeaderGridView) view.findViewById(R.id.theme_listview);
        addHeaderViews(inflater);
        mGridView.setRecyclerListener(this);
        mGridView.setOnScrollListener(this);
    }

    private void addMainHeader(LayoutInflater inflater) {
        View header = inflater.inflate(R.layout.theme_grid_cardview_header, null);
        mCurrentThemeTextView = (TextView) header.findViewById(R.id.header_theme_text);

        setThemeNameIfAlreadyAvailable();
        mThemeBrowserActivity.fetchCurrentTheme();
        LinearLayout customize = (LinearLayout) header.findViewById(R.id.customize);
        customize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onTryAndCustomizeSelected(mCurrentThemeId);
            }
        });

        LinearLayout details = (LinearLayout) header.findViewById(R.id.details);
        details.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onDetailsSelected(mCurrentThemeId);
            }
        });

        LinearLayout support = (LinearLayout) header.findViewById(R.id.support);
        support.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onSupportSelected(mCurrentThemeId);
            }
        });

        mGridView.addHeaderView(header);
    }

    private void setThemeNameIfAlreadyAvailable() {
        ThemeModel currentTheme = mThemeBrowserActivity.getCurrentTheme();
        if (currentTheme != null) {
            mCurrentThemeTextView.setText(currentTheme.getName());
        }
    }

    public void setRefreshing(boolean refreshing) {
        mShouldRefreshOnStart = refreshing;
        if (mSwipeToRefreshHelper != null) {
            mSwipeToRefreshHelper.setRefreshing(refreshing);
            if (!refreshing) {
                refreshView(getSpinnerPosition());
            }
        }
    }

    private void configureAndAddSearchHeader(LayoutInflater inflater) {
        View headerSearch = inflater.inflate(R.layout.theme_grid_cardview_header_search, null);
        headerSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onSearchClicked();
            }
        });
        configureFilterSpinner(headerSearch);
        ImageButton searchButton = (ImageButton) headerSearch.findViewById(R.id.theme_search);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onSearchClicked();
            }
        });
    }

    private void configureFilterSpinner(View headerSearch) {
        mSpinner = (Spinner) headerSearch.findViewById(R.id.theme_filter_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(mThemeBrowserActivity, R.array.themes_filter_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(adapter);
        mGridView.addHeaderView(headerSearch);
        mSpinner.setOnItemSelectedListener(this);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mPage = savedInstanceState.getInt(BUNDLE_PAGE, 1);
        }
    }

    private void setEmptyViewVisible(boolean visible) {
        if (getView() == null || !isAdded()) {
            return;
        }
        mEmptyView.setVisibility(visible ? RelativeLayout.VISIBLE : RelativeLayout.GONE);
        mGridView.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible && !NetworkUtils.isNetworkAvailable(mThemeBrowserActivity)) {
            mEmptyTextView.setText(R.string.no_network_title);
        }
    }

    protected Cursor fetchThemes(int position) {
        if (mSite == null) {
            return null;
        }

        String blogId = String.valueOf(mSite.getSiteId());
        switch (position) {
            case THEME_FILTER_PREMIUM_INDEX:
                return null;
            case THEME_FILTER_ALL_INDEX:
                return mThemeStore.getWpThemesCursor();
            case THEME_FILTER_FREE_INDEX:
            default:
                return mThemeStore.getWpThemesCursor();
        }
    }

    protected void refreshView(int position) {
        Cursor cursor = fetchThemes(position);
        if (cursor == null) {
            return;
        }
        if (mAdapter == null) {
            mAdapter = new ThemeBrowserAdapter(mThemeBrowserActivity, cursor, false, mCallback);
        }
        if (mNoResultText.isShown()) {
            mNoResultText.setVisibility(View.GONE);
        }
        mAdapter.changeCursor(cursor);
        mAdapter.notifyDataSetChanged();
        setEmptyViewVisible(mAdapter.getCount() == 0);
        mProgressBar.setVisibility(View.GONE);
    }

    private boolean shouldFetchThemesOnScroll(int lastVisibleCount, int totalItemCount) {
        if (totalItemCount < ThemeBrowserActivity.THEME_FETCH_MAX) {
            return false;
        } else {
            int numberOfColumns = mGridView.getNumColumns();
            return lastVisibleCount >= totalItemCount - numberOfColumns;
        }
    }

    protected int getSpinnerPosition() {
        if (mSpinner != null) {
            return mSpinner.getSelectedItemPosition();
        } else {
            return 0;
        }
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        // cancel image fetch requests if the view has been moved to recycler.
        WPNetworkImageView niv = (WPNetworkImageView) view.findViewById(R.id.theme_grid_item_image);
        if (niv != null) {
            // this tag is set in the ThemeBrowserAdapter class
            String requestUrl = (String) niv.getTag();
            if (requestUrl != null) {
                // need a listener to cancel request, even if the listener does nothing
                ImageContainer container = WordPress.sImageLoader.get(requestUrl, new ImageListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                    }

                    @Override
                    public void onResponse(ImageContainer response, boolean isImmediate) {
                    }

                });
                container.cancelRequest();
            }
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (mSpinner != null) {
            refreshView(position);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (shouldFetchThemesOnScroll(firstVisibleItem + visibleItemCount, totalItemCount) && NetworkUtils.isNetworkAvailable(getActivity())) {
            mProgressBar.setVisibility(View.VISIBLE);
        }
    }
}

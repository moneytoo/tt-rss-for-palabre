package com.brouken.palabre.ttrss;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.RequestFuture;
import com.levelup.palabre.api.ExtensionUpdateStatus;
import com.levelup.palabre.api.PalabreExtension;
import com.levelup.palabre.api.datamapping.Article;
import com.levelup.palabre.api.datamapping.Category;
import com.levelup.palabre.api.datamapping.Source;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TinyExtension extends PalabreExtension {

    public static final int ARTICLES_IN_RESPONSE = 200;

    RequestQueue mRequestQueue;

    String mUrlApi;

    String mSid;
    String mIconsPath;

    List<Integer> mCategories;
    List<Integer> mFeeds;
    int mLastArticleId;

    List<Source> mPreviousSources;

    String prefUrl;
    boolean prefSingleUserMode = false;
    String prefLogin;
    String prefPassword;

    public static boolean prefBasicAuth = false;
    public static String prefHttpLogin;
    public static String prefHttpPassword;

    public static void log(String text) {
        if (BuildConfig.DEBUG)
            Log.d("Palabre", text);
    }

    @Override
    protected void onSavedArticles(List<String> articles, boolean value) {
        log("onSavedArticles() " + value);

        try {
            updateArticles(articles, true, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onReadArticlesBefore(String type, String uniqueIds, long timestamp) {
        log("onReadArticlesBefore() " + type + "; " + uniqueIds + "; " + timestamp);

        List<String> readArticleIds = new ArrayList<>();
        List<Long> parentIds = new ArrayList<>();

        if (type.equals("feeds")) {
            parentIds.add(Source.getByUniqueId(this, uniqueIds).getId());
        } else if (type.equals("categories")) {
            for (Source source : Source.getAllWithCategories(this)) {
                for (Category category : source.getCategories()) {
                    if (category.getUniqueId().equals(uniqueIds)) {
                        log("adding " + source.getId() + " " + source.getTitle());
                        parentIds.add(source.getId());
                        break;
                    }
                }
            }
        }

        for (Article article : Article.getAll(this)) {
            if ((((type.equals("feeds") || type.equals("categories")) &&  parentIds.contains(article.getSourceId())) || type.equals("all")) &&
                    // following line was commented out in test version sent to one person
                    !article.isRead() && // Ignore read state as it's buggy/cached (?) when used in "Mark all" actions
                    article.getDate().getTime() <= timestamp) {
                log("adding uniqueId=" + article.getUniqueId() + " " + article.getDate().getTime() + " read=" + article.isRead() + " " + article.getTitle());
                readArticleIds.add(article.getUniqueId());
            }
        }

        try {
            updateArticles(readArticleIds, false, false);
        } catch (Exception e) {
            onReadArticlesBeforeFailed(type, uniqueIds, timestamp);
        }
    }

    @Override
    protected void onReadArticles(List<String> articles, boolean read) {
        init();
        log("onReadArticles " + articles.size());

        try {
            updateArticles(articles, false, !read);
        } catch (Exception e) {
            onReadArticlesFailed(articles, read);
        }
    }

    @Override
    protected void onUpdateData() {

        log("onUpdateData() started");

        publishUpdateStatus(new ExtensionUpdateStatus().start());

        try {
            mCategories = new ArrayList<>();
            mFeeds = new ArrayList<>();

            loadPreferences();
            log("Last article ID: " + mLastArticleId);
            init();

            fetchAll();
            publishUpdateStatus(new ExtensionUpdateStatus().stop());
        } catch (Exception e) {
            e.printStackTrace();
            log(e.getMessage());
            publishUpdateStatus(new ExtensionUpdateStatus().fail(e.getMessage()));
        }

        log("onUpdateData() finished");

        stopSelf();
    }

    void init() {
        mUrlApi = prefUrl + "/api/";

        if (mRequestQueue == null) {
            Cache cache = new DiskBasedCache(getCacheDir(), 1024 * 1024); // 1MB cap
            Network network = new BasicNetwork(new HurlStack());
            mRequestQueue = new RequestQueue(cache, network);

            mRequestQueue.start();
        }
    }

    void fetchAll() throws JSONException, InterruptedException, ExecutionException, TimeoutException {
        mPreviousSources = Source.getAll(this);

        JSONObject response = getResponse(buildAuthRequestJSON());
        mSid = getSid(response);

        publishUpdateStatus(new ExtensionUpdateStatus().progress(5));
        getConfig();
        publishUpdateStatus(new ExtensionUpdateStatus().progress(10));
        getCategories();
        publishUpdateStatus(new ExtensionUpdateStatus().progress(15));
        getFeeds();
        publishUpdateStatus(new ExtensionUpdateStatus().progress(25));
        getHeadlines();

        savePreferences();
    }

    JSONObject getResponse(JSONObject requestJSON) throws InterruptedException, ExecutionException, TimeoutException {
        RequestFuture<JSONObject> requestFuture = RequestFuture.newFuture();
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequestBasicAuth(Request.Method.POST, mUrlApi, requestJSON, requestFuture, requestFuture);
        mRequestQueue.add(jsonObjectRequest);
        return requestFuture.get(30, TimeUnit.SECONDS);
    }

    void getConfig() throws InterruptedException, ExecutionException, TimeoutException, JSONException {
        JSONObject response = getResponse(buildConfigRequestJSON());
        processConfig(response.getJSONObject("content"));
    }

    void getCategories() throws InterruptedException, ExecutionException, TimeoutException, JSONException {
        JSONObject response = getResponse(buildCategoriesRequestJSON());
        processCategories(response.getJSONArray("content"));
    }

    void getFeeds() throws InterruptedException, ExecutionException, TimeoutException, JSONException {
        // TODO: Nested categories may cause this to double up on feeds
        for (Integer categoryId : mCategories) {
            JSONObject response = getResponse(buildFeedsRequestJSON(categoryId));
            processFeeds(response.getJSONArray("content"));
        }
        processFeedsCleanup();
    }

    float incrementProgress(float progress) {
        publishUpdateStatus(new ExtensionUpdateStatus().progress(Math.round(progress)));
        return progress;
    }

    void getHeadlines() throws JSONException, InterruptedException, ExecutionException, TimeoutException {
        //log("getHeadlines");

        final boolean firstRun = mLastArticleId <= 0;
        List<String> unreadArticleIds = new ArrayList<>();
        List<String> articleIds;

        // Determine progress counter
        float progress = 25;
        List<Article> savedArticles = Article.getAll(this);
        JSONObject unreadResponse = getResponse(buildUnreadCountRequestJSON());
        float unreadCount = unreadResponse.getJSONObject("content").getInt("unread");
        float feedCount = mFeeds.size();
        float savedCount = savedArticles.size();
        float progressIncrease = 1;
        if (firstRun) {
            // Progress is number of unread, feedCount
            if (unreadCount > ARTICLES_IN_RESPONSE)
                progressIncrease = (95 - progress) / ((unreadCount / ARTICLES_IN_RESPONSE) + feedCount);
            else
                progressIncrease = (95 - progress) + feedCount;
        } else {
            // Progress is number of unread, number of saved, number new articles (assume less than 200, so 1)
            if (unreadCount > ARTICLES_IN_RESPONSE)
                progressIncrease = (95 - progress) / ((unreadCount / ARTICLES_IN_RESPONSE) + (savedCount/1000) + 1);
            else
                progressIncrease = (95 - progress) + savedCount + 1;
        }
        log("Progress - pi:" + progressIncrease + " uc:" + unreadCount + " fc:" + feedCount + " sc:" + savedCount);

        // Get ids of all unread articles (also save them on first run)
        log("Fetching unread.");
        int skip = 0;
        if (unreadCount > 0) {
            do {
                JSONObject response = getResponse(buildHeadlinesUnreadRequestJSON(skip, firstRun));
                articleIds = processHeadlines(response.getJSONArray("content"), firstRun);
                unreadArticleIds.addAll(articleIds);
                skip += articleIds.size();
                progress = incrementProgress(progress+progressIncrease);
            } while (articleIds.size() > 0);
        }
        log("Finished fetching unread.");

        // Update read state of articles viewed outside
        if (!firstRun) {
            log("Updating read state.");
            for (Article savedArticle : savedArticles) {
                boolean isRead = !unreadArticleIds.contains(savedArticle.getUniqueId());
                progress = incrementProgress(progress+progressIncrease);

                if (savedArticle.isRead() == isRead)
                    continue;

                savedArticle.setRead(isRead);
                savedArticle.save(this);
            }
            log("Finished updating read state.");
        }

        // Get an extra few articles on first run (10 per feed)
        if (firstRun) {
            log("Grabbing 10 from each feed.");
            for (Integer feedId : mFeeds) {
                JSONObject response = getResponse(buildHeadlinesFeedRequestJSON(feedId));
                processHeadlines(response.getJSONArray("content"), true);
                progress = incrementProgress(progress+progressIncrease);
            }
            log("Finished grabbing 10 from each feed.");
        } else {
            // Get all new articles since previous sync
            log("Getting new articles since previous sync.");
            skip = 0;
            do {
                // TODO: We grabbed all unread articles before, this is just going to overlap those with the read articles, too. Perhaps change to read?
                JSONObject response = getResponse(buildHeadlinesRequestJSON(skip));
                articleIds = processHeadlines(response.getJSONArray("content"), true);
                skip += articleIds.size();
            } while (articleIds.size() > 0);
            progress = incrementProgress(progress+progressIncrease);
            log("Finished getting new articles since previous sync.");
        }
    }

    void updateArticles(List<String> articles, boolean starredMode, boolean marked) throws JSONException, InterruptedException, ExecutionException, TimeoutException {
        for (String article : articles) {
            log("article to update: " + article + " unread/starred=" + marked);
        }

        String articleIds = TextUtils.join(",", articles);
        JSONObject response = getResponse(buildArticleUpdateRequestJSON(articleIds, starredMode, marked));

        log(response.toString());
    }

    JSONObject buildAuthRequestJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "login");
        if (!prefSingleUserMode) {
            json.put("user", prefLogin);
            json.put("password", prefPassword);
        }
        return json;
    }

    String getSid(JSONObject json) throws JSONException {
        return json.getJSONObject("content").getString("session_id");
    }

    JSONObject buildUnreadCountRequestJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getUnread");
        json.put("sid", mSid);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildUnreadCountRequestJSON.json");
        return json;
    }

    JSONObject buildConfigRequestJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getConfig");
        json.put("sid", mSid);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildConfigRequestJSON.json");
        return json;
    }

    JSONObject buildCategoriesRequestJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getCategories");
        json.put("sid", mSid);
        json.put("unread_only", false);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildCategoriesRequestJSON.json");
        return json;
    }

    JSONObject buildFeedsRequestJSON(int categoryId) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getFeeds");
        json.put("sid", mSid);
        json.put("cat_id", categoryId);
        json.put("unread_only", false);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildFeedsRequestJSON.json");
        return json;
    }

    JSONObject buildHeadlinesUnreadRequestJSON(int skip, boolean firstRun) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getHeadlines");
        json.put("sid", mSid);
        json.put("feed_id", -4);
        json.put("view_mode", "unread");
        json.put("show_content", firstRun);
        json.put("show_excerpt", false);
        json.put("include_attachments", firstRun);
        json.put("limit", ARTICLES_IN_RESPONSE);
        json.put("skip", skip);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildHeadlinesUnreadRequestJSON.json");
        return json;
    }

    JSONObject buildHeadlinesFeedRequestJSON(int feedId) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getHeadlines");
        json.put("sid", mSid);
        json.put("feed_id", feedId);
        json.put("view_mode", "all_articles");
        json.put("show_content", true);
        json.put("show_excerpt", false);
        json.put("include_attachments", true);
        json.put("limit", 10);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildHeadlinesFeedRequestJSON.json");
        return json;
    }

    JSONObject buildHeadlinesRequestJSON(int skip) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "getHeadlines");
        json.put("sid", mSid);
        json.put("feed_id", -4);
        json.put("view_mode", "all_articles");
        json.put("show_content", true);
        json.put("show_excerpt", false);
        json.put("include_attachments", true);
        json.put("since_id", mLastArticleId);
        json.put("limit", ARTICLES_IN_RESPONSE);
        json.put("skip", skip);
        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildHeadlinesRequestJSON.json");
        return json;
    }

    JSONObject buildArticleUpdateRequestJSON(String articleIds, boolean starredMode, boolean marked) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("op", "updateArticle");
        json.put("sid", mSid);
        json.put("article_ids", articleIds);

        // Set article read/unread
        json.put("field", starredMode ? 0 : 2);
        json.put("mode", marked ? 1 : 0);

        //log("curl -d '" + json.toString() + "' " + mUrlApi + " > /tmp/ttrss/buildArticleUpdateRequestJSON.json");
        return json;
    }

    void processConfig(JSONObject json) throws JSONException {
        // "icons_url":"feed-icons"
        mIconsPath = json.getString("icons_url");
    }

    void processCategories(JSONArray jsonCategories) throws JSONException {

        List<Category> categories = new ArrayList<>();

        List<JSONObject> jsonArray = new ArrayList<>();
        for (int i = 0; i < jsonCategories.length(); i++) {
            jsonArray.add(jsonCategories.getJSONObject(i));
        }

        Collections.sort(jsonArray, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject o1, JSONObject o2) {
                try {
                    Integer order1 = 0;
                    if (o1.has("order_id"))
                        order1 = o1.getInt("order_id");
                    Integer order2 = 0;
                    if (o2.has("order_id"))
                        order2 = o2.getInt("order_id");
                    return  order1.compareTo(order2);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return 0;
            }
        });

        for (JSONObject jsonCategory : jsonArray) {
            final String title = jsonCategory.getString("title");
            final int id = jsonCategory.getInt("id");

            if (id < 0)
                continue;

            Category category = new Category()
                    .uniqueId(Integer.toString(id))
                    .title(title);

            mCategories.add(id);

            categories.add(category);
        }

        List<Category> previousCategories = Category.getAll(this);

        Category.multipleSave(this, categories);

        // Delete categories no longer on server
        for (Category previousCategory : previousCategories) {
            int previousId = Integer.parseInt(previousCategory.getUniqueId());
            if (!mCategories.contains(previousId))
                previousCategory.delete(this);
        }
    }

    void processFeeds(JSONArray jsonFeeds) throws JSONException {

        List<Source> sources = new ArrayList<>();

        // Feed/Source order is ignored in Palabre (?)
        List<JSONObject> jsonArray = new ArrayList<>();
        for (int i = 0; i < jsonFeeds.length(); i++) {
            jsonArray.add(jsonFeeds.getJSONObject(i));
        }

        Collections.sort(jsonArray, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject o1, JSONObject o2) {
                try {
                    String title1 = o1.getString("title");
                    String title2 = o2.getString("title");
                    return  title1.compareToIgnoreCase(title2);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return 0;
            }
        });

        for (JSONObject jsonFeed : jsonArray) {
            final String title = jsonFeed.getString("title");
            final int id = jsonFeed.getInt("id");
            final int categoryId = jsonFeed.getInt("cat_id");

            if (id < 0)
                continue;

            Source source = new Source()
                    .uniqueId(Integer.toString(id))
                    .title(title);

            if (jsonFeed.has("feed_url")) {
                final String url = jsonFeed.getString("feed_url");
                source.setDataUrl(url);
            }

            if (jsonFeed.has("has_icon")) {
                final boolean hasIcon = jsonFeed.getBoolean("has_icon");
                if (hasIcon)
                    source.setIconUrl(prefUrl + "/" + mIconsPath + "/" + id + ".ico");
            }

            final Category category = Category.getByUniqueId(this, Integer.toString(categoryId));
            source.getCategories().add(category);

            mFeeds.add(id);

            sources.add(source);
        }

        Source.multipleSave(this, sources);
    }

    void processFeedsCleanup() {
        // Delete sources no longer on server
        for (Source previousSource : mPreviousSources) {
            int previousId = Integer.parseInt(previousSource.getUniqueId());
            if (!mFeeds.contains(previousId))
                previousSource.delete(this);
        }
    }

    List<String> processHeadlines(JSONArray jsonHeadlines, boolean fullProcess) throws JSONException {
        List<Article> articles = new ArrayList<>();
        List<String> articleIds = new ArrayList<>();
        log("Processing headlines full=" + fullProcess);

        for (int i = 0; i < jsonHeadlines.length(); i++) {
            JSONObject jsonHeadline = (JSONObject) jsonHeadlines.get(i);
            //log("ARTICLE: " + jsonHeadline.toString());

            final int id = jsonHeadline.getInt("id");
            articleIds.add(Integer.toString(id));

            if (!fullProcess)
                continue;

            final String title = jsonHeadline.getString("title");
            final int feedId = jsonHeadline.getInt("feed_id");
            final String author = jsonHeadline.getString("author");
            final String linkUrl = jsonHeadline.getString("link");
            final boolean read = !jsonHeadline.getBoolean("unread");
            final String fullContent = jsonHeadline.getString("content");
            final long date = jsonHeadline.getInt("updated");
            final boolean marked = jsonHeadline.getBoolean("marked");

            Article article = new Article()
                    .uniqueId(Integer.toString(id))
                    .title(title)
                    .author(author)
                    .linkUrl(linkUrl)
                    .read(read)
                    .content(fullContent)
                    .date(new Date(date))
                    .saved(marked);

            // Check if there is an image
            if (!jsonHeadline.isNull("attachments")) {
                JSONArray jsonAttachments = jsonHeadline.getJSONArray("attachments");
                String image = "";
                int largestWidth = 0;
                for (int j = 0; j < jsonAttachments.length(); j++) {
                    JSONObject jsonAttachment = (JSONObject) jsonAttachments.get(j);
                    if (!jsonAttachment.isNull("content_url")) {
                        if (image == "")
                            image = jsonAttachment.getString("content_url");
                        if (!jsonHeadline.isNull("width")) {
                            int width = jsonAttachment.getInt("width");
                            if (width > largestWidth) {
                                largestWidth = width;
                                image = jsonAttachment.getString("content_url");
                            }
                        }
                    }
                }
                if (image != "") {
                    article.setImage(image);
                } else {
                    if (!jsonHeadline.isNull("content")) {
                        Pattern p = Pattern.compile("(?<=src=\")(.*?)(?=\")|(?<=src=')(.*?)(?=')");
                        Matcher matcher = p.matcher(jsonHeadline.getString("content"));
                        if (matcher.find()){
                            article.setImage(matcher.group(0));
                        }
                    }
                }
            }

            // TODO: What does this do? If it's the feed ID, can it be done outside the loop to speed things up?
            article.setSourceId(Source.getByUniqueId(this, Integer.toString(feedId)).getId());

            articles.add(article);
            //article.save(this);

            if (id > mLastArticleId)
                mLastArticleId = id;
        }

        if (articles.size() > 0)
            Article.multipleSave(this, articles);

        return articleIds;
    }

    void loadPreferences() throws Exception {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mLastArticleId = sharedPreferences.getInt("lastArticleId", -1);

        prefUrl = sharedPreferences.getString("pref_url", null);
        prefLogin = sharedPreferences.getString("pref_login", null);
        prefBasicAuth = sharedPreferences.getBoolean("pref_basic_auth", false);
        prefPassword = sharedPreferences.getString("pref_password", null);
        prefHttpLogin = sharedPreferences.getString("pref_http_login", null);
        prefHttpPassword = sharedPreferences.getString("pref_http_password", null);
        prefSingleUserMode = sharedPreferences.getBoolean("pref_single_user_mode", prefSingleUserMode);

        if (prefUrl == null)
            throw new Exception("URL not set");
    }

    void savePreferences() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        // TODO: If the app crashes or is closed, sync will have to start over. Perhaps do this inside the fetch loops after a processArticle call? Also, how will this work if unread is grabbed before read?
        editor.putInt("lastArticleId", mLastArticleId);
        editor.apply();
    }

}

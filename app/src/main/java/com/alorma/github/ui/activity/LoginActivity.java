package com.alorma.github.ui.activity;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.StyleRes;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Toast;

import com.alorma.github.BuildConfig;
import com.alorma.github.Interceptor;
import com.alorma.github.R;
import com.alorma.github.basesdk.ApiClient;
import com.alorma.github.basesdk.client.BaseClient;
import com.alorma.github.basesdk.client.credentials.GithubDeveloperCredentials;
import com.alorma.github.sdk.bean.dto.response.Token;
import com.alorma.github.sdk.bean.dto.response.User;
import com.alorma.github.sdk.login.AccountsHelper;
import com.alorma.github.sdk.security.GitHub;
import com.alorma.github.sdk.services.login.RequestTokenClient;
import com.alorma.github.sdk.services.user.GetAuthUserClient;
import com.alorma.github.ui.ErrorHandler;
import com.alorma.github.ui.adapter.AccountsAdapter;
import com.android.vending.billing.IInAppBillingService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import dmax.dialog.SpotsDialog;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class LoginActivity extends AccountAuthenticatorActivity implements BaseClient.OnResultCallback<User> {

    public static final String ARG_ACCOUNT_TYPE = "ARG_ACCOUNT_TYPE";
    public static final String ARG_AUTH_TYPE = "ARG_AUTH_TYPE";
    public static final String ADDING_FROM_ACCOUNTS = "ADDING_FROM_ACCOUNTS";
    public static final String ADDING_FROM_APP = "ADDING_FROM_APP";
    private static final String SKU_MULTI_ACCOUNT = "com.alorma.github.multiaccount";

    public static String OAUTH_URL = "https://github.com/login/oauth/authorize";

    private SpotsDialog progressDialog;
    private String accessToken;
    private String scope;
    private RequestTokenClient requestTokenClient;

    private IInAppBillingService mService;

    ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name,
                                       IBinder service) {
            mService = IInAppBillingService.Stub.asInterface(service);
        }
    };
    private Account[] accounts;
    private String purchaseId;

    /**
     * There is three ways to get to this activity:
     * 1. From Android launcher.
     * 2. After user's login.
     * 3. From application, it means, user wants to switch accounts.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        createBillingService();

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);

        final View loginButton = findViewById(R.id.login);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                login();
            }
        });

        enableCreateGist(false);

        AccountManager accountManager = AccountManager.get(this);

        accounts = accountManager.getAccountsByType(getString(R.string.account_type));

        final boolean fromLogin = getIntent().getData() != null &&
                getIntent().getData().getScheme().equals(getString(R.string.oauth_scheme));
        final boolean fromAccounts = getIntent().getBooleanExtra(ADDING_FROM_ACCOUNTS, false);
        final boolean fromApp = getIntent().getBooleanExtra(ADDING_FROM_APP, false);

        toolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (fromApp) {                      // TODO back button should have the same logic (issue #137)
                    openMain();
                } else {
                    finish();
                }
            }
        });

        final RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        final AccountsAdapter adapter = new AccountsAdapter(this, accounts);
        recyclerView.setAdapter(adapter);

        if (accounts != null) {
            for (Account account : accounts) {
                checkAndEnableSyncAdapter(account);
            }
        }

        if (fromLogin) {
            loginButton.setEnabled(false);
            showProgressDialog(R.style.SpotDialog_Login);
            Uri uri = getIntent().getData();
            String code = uri.getQueryParameter("code");

            if (requestTokenClient == null) {
                requestTokenClient = new RequestTokenClient(LoginActivity.this, code);
                requestTokenClient.setOnResultCallback(new BaseClient.OnResultCallback<Token>() {
                    @Override
                    public void onResponseOk(Token token, Response r) {
                        if (token.access_token != null) {
                            endAccess(token.access_token, token.scope);
                        } else if (token.error != null) {
                            Toast.makeText(LoginActivity.this, token.error, Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFail(RetrofitError error) {
                        ErrorHandler.onError(LoginActivity.this, "WebViewCustomClient", error);
                    }
                });
                requestTokenClient.execute();
            }
        } else if (fromAccounts) {
            login();
        } else if (!fromApp && accounts != null && accounts.length > 0) {
            openMain();
        }
    }

    private void createBillingService() {
        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        serviceIntent.setPackage("com.android.vending");
        bindService(serviceIntent, mServiceConn, Context.BIND_AUTO_CREATE);
    }

    public void showProgressDialog(@StyleRes int style) {
        try {
            progressDialog = new SpotsDialog(this, style);
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void login() {
        if (multipleAccountFeatureRequired()) {
            SKUTask task = new SKUTask();
            task.execute(SKU_MULTI_ACCOUNT);
        } else {
            openExternalLogin(new GitHub());
        }
    }

    private boolean multipleAccountFeatureRequired() {
        return !BuildConfig.DEBUG && accounts != null && accounts.length > 0;
    }

    private void openExternalLogin(ApiClient client) {
        final String url = String.format("%s?client_id=%s&scope=gist,user,notifications,repo",
                OAUTH_URL, GithubDeveloperCredentials.getInstance().getProvider().getApiClient());

        final List<ResolveInfo> browserList = getBrowserList();

        final List<LabeledIntent> intentList = new ArrayList<>();

        for (final ResolveInfo resolveInfo : browserList) {
            final Intent newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            newIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName,
                    resolveInfo.activityInfo.name));

            intentList.add(new LabeledIntent(newIntent,
                    resolveInfo.resolvePackageName,
                    resolveInfo.labelRes,
                    resolveInfo.icon));
        }

        final Intent chooser = Intent.createChooser(intentList.remove(0), "Choose your favorite browser");
        LabeledIntent[] extraIntents = intentList.toArray(new LabeledIntent[intentList.size()]);
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents);

        startActivity(chooser);
    }

    private List<ResolveInfo> getBrowserList() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://sometesturl.com"));

        return getPackageManager().queryIntentActivities(intent, 0);
    }

    private class SKUTask extends AsyncTask<String, Void, Bundle> {

        @Override
        protected Bundle doInBackground(String... strings) {
            ArrayList<String> skuList = new ArrayList<>();
            Collections.addAll(skuList, strings);
            Bundle querySkus = new Bundle();
            querySkus.putStringArrayList("ITEM_ID_LIST", skuList);

            try {
                return mService.getPurchases(3, getPackageName(), "inapp", null);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bundle ownedItems) {
            super.onPostExecute(ownedItems);
            if (ownedItems != null) {
                int response = ownedItems.getInt("RESPONSE_CODE");
                if (response == 0) {
                    ArrayList<String> ownedSkus = ownedItems.getStringArrayList("INAPP_PURCHASE_ITEM_LIST");

                    if (ownedSkus.size() == 0) {
                        showDialogBuyMultiAccount();
                    } else {
                        openExternalLogin(new GitHub());
                    }
                }
            }
        }
    }

    private void showDialogBuyMultiAccount() {
        try {
            purchaseId = UUID.randomUUID().toString();
            Bundle buyIntentBundle = mService.getBuyIntent(3, getPackageName(),
                    SKU_MULTI_ACCOUNT, "inapp", purchaseId);
            PendingIntent pendingIntent = buyIntentBundle.getParcelable("BUY_INTENT");
            startIntentSenderForResult(pendingIntent.getIntentSender(),
                    1001, new Intent(), 0, 0, 0);
        } catch (RemoteException | IntentSender.SendIntentException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1001) {
            String purchaseData = data.getStringExtra("INAPP_PURCHASE_DATA");

            if (resultCode == RESULT_OK) {
                try {
                    JSONObject jo = new JSONObject(purchaseData);
                    String sku = jo.getString("productId");
                    String developerPayload = jo.getString("developerPayload");
                    if (developerPayload.equals(purchaseId) && SKU_MULTI_ACCOUNT.equals(sku)) {
                        openExternalLogin(new GitHub());
                    }
                } catch (JSONException e) {

                    e.printStackTrace();
                }
            }
        }
    }

    private void enableCreateGist(boolean b) {
        int flag = b ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        ComponentName componentName = new ComponentName(this, Interceptor.class);
        getPackageManager().setComponentEnabledSetting(componentName, flag, PackageManager.DONT_KILL_APP);
    }

    private void openMain() {
        enableCreateGist(true);
        MainActivity.startActivity(LoginActivity.this);
        finish();
    }

    private void endAccess(String accessToken, String scope) {
        this.accessToken = accessToken;
        this.scope = scope;

        progressDialog.setMessage(getString(R.string.loading_user));

        GetAuthUserClient userClient = new GetAuthUserClient(this, accessToken);
        userClient.setOnResultCallback(this);
        userClient.execute();
    }

    @Override
    public void onResponseOk(User user, Response r) {
        Account account = new Account(user.login, getString(R.string.account_type));
        Bundle userData = AccountsHelper.buildBundle(user.name, user.email, user.avatar_url, scope);
        userData.putString(AccountManager.KEY_AUTHTOKEN, accessToken);

        AccountManager accountManager = AccountManager.get(this);

        accountManager.addAccountExplicitly(account, null, userData);
        accountManager.setAuthToken(account, getString(R.string.account_type), accessToken);

        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        result.putString(AccountManager.KEY_AUTHTOKEN, accessToken);

        setAccountAuthenticatorResult(result);

        checkAndEnableSyncAdapter(account);

        openMain();
    }

    private void checkAndEnableSyncAdapter(Account account) {
        if (!ContentResolver.isSyncActive(account, getString(R.string.account_type))) {
            ContentResolver.setIsSyncable(account, getString(R.string.account_type), ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE);
            ContentResolver.addPeriodicSync(
                    account,
                    getString(R.string.account_type),
                    Bundle.EMPTY,
                    1800);
        }
    }

    @Override
    public void onFail(RetrofitError error) {

    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mService != null) {
            unbindService(mServiceConn);
        }
    }
}

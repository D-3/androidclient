/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.io.File;

import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.ServerList;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.service.ServerListUpdater;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.service.msgcenter.PushServiceManager;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;

import static org.kontalk.crypto.PersonalKeyImporter.KEYPACK_FILENAME;


/**
 * Preferences activity.
 * TODO convert to fragments layout
 * @author Daniele Ricci
 */
public final class PreferencesActivity extends PreferenceActivity {
    private static final String TAG = Kontalk.TAG;

    private static final int REQUEST_PICK_BACKGROUND = Activity.RESULT_FIRST_USER + 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // no account - redirect to bootstrap preferences
        if (Authenticator.getDefaultAccount(this) == null) {
            startActivity(new Intent(this, BootstrapPreferences.class));
            finish();
            return;
        }

        addPreferencesFromResource(R.xml.preferences);

        setupActivity();

        // push notifications checkbox
        final Preference pushNotifications = findPreference("pref_push_notifications");
        pushNotifications.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                CheckBoxPreference pref = (CheckBoxPreference) preference;
                if (pref.isChecked())
                    MessageCenterService.enablePushNotifications(getApplicationContext());
                else
                    MessageCenterService.disablePushNotifications(getApplicationContext());

                return true;
            }
        });

        // message center restart
        final Preference restartMsgCenter = findPreference("pref_restart_msgcenter");
        restartMsgCenter.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Log.w(TAG, "manual message center restart requested");
                MessageCenterService.restart(getApplicationContext());
                Toast.makeText(PreferencesActivity.this, R.string.msg_msgcenter_restarted, Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        // change passphrase
        final Preference changePassphrase = findPreference("pref_change_passphrase");
        changePassphrase.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {

                if (Authenticator.isUserPassphrase(PreferencesActivity.this)) {

                    OnPassphraseRequestListener action = new OnPassphraseRequestListener() {
                        public void onValidPassphrase(String passphrase) {
                            askNewPassphrase();
                        }

                        public void onInvalidPassphrase() {
                            new AlertDialog.Builder(PreferencesActivity.this)
                                .setTitle(R.string.title_passphrase)
                                .setMessage(R.string.err_password_invalid)
                                .show();
                        }
                    };
                    askCurrentPassphrase(action);
                }

                else {
                    askNewPassphrase();
                }


                return true;
            }
        });

        // regenerate key pair
        final Preference regenKeyPair = findPreference("pref_regenerate_keypair");
        regenKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(PreferencesActivity.this)
                        .setTitle(R.string.pref_regenerate_keypair)
                        .setMessage(R.string.pref_regenerate_keypair_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                Toast.makeText(PreferencesActivity.this, R.string.msg_generating_keypair,
                                        Toast.LENGTH_LONG).show();

                                MessageCenterService.regenerateKeyPair(getApplicationContext());
                            }
                        })
                        .show();

                return true;
            }
        });

        // export key pair
        final Preference exportKeyPair = findPreference("pref_export_keypair");
        exportKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {

                // TODO check for external storage presence

                final OnPassphraseChangedListener action = new OnPassphraseChangedListener() {
                    public void onPassphraseChanged(String passphrase) {
                        try {

                            ((Kontalk)getApplicationContext()).exportPersonalKey(passphrase);

                            Toast.makeText(PreferencesActivity.this,
                                R.string.msg_keypair_exported,
                                Toast.LENGTH_LONG).show();

                        }
                        catch (Exception e) {

                            Log.e(TAG, "error exporting keys", e);
                            Toast.makeText(PreferencesActivity.this,
                                // TODO i18n
                                "Unable to export personal key.",
                                Toast.LENGTH_LONG).show();

                        }
                    }
                };

                // passphrase was never set by the user
                // encrypt it with a user-defined passphrase first
                if (!Authenticator.isUserPassphrase(PreferencesActivity.this)) {
                    askNewPassphrase(action);
                }

                else {
                    OnPassphraseRequestListener action2 = new OnPassphraseRequestListener() {
                        public void onValidPassphrase(String passphrase) {
                            action.onPassphraseChanged(passphrase);
                        }

                        public void onInvalidPassphrase() {
                            new AlertDialog.Builder(PreferencesActivity.this)
                                .setTitle(R.string.pref_export_keypair)
                                .setMessage(R.string.err_password_invalid)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        }
                    };

                    askCurrentPassphrase(action2);
                }

                return true;
            }
        });

        // import key pair
        final Preference importKeyPair = findPreference("pref_import_keypair");
        importKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(PreferencesActivity.this)
                    .setTitle(R.string.pref_import_keypair)
                    .setMessage(getString(R.string.msg_import_keypair, KEYPACK_FILENAME))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Uri keypack = Uri.fromFile(new File(Environment
                                .getExternalStorageDirectory(), KEYPACK_FILENAME));
                            MessageCenterService.importKeyPair(getApplicationContext(),
                                keypack, ((Kontalk) getApplication()).getCachedPassphrase());
                        }
                    })
                    .show();

                return true;
            }
        });

        // delete account
        final Preference deleteAccount = findPreference("pref_delete_account");
        deleteAccount.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(PreferencesActivity.this)
                    .setTitle(R.string.pref_delete_account)
                    .setMessage(R.string.msg_delete_account)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // progress dialog
                            final LockedProgressDialog progress = new LockedProgressDialog(PreferencesActivity.this);
                            progress.setMessage(getString(R.string.msg_delete_account_progress));
                            progress.setIndeterminate(true);
                            progress.show();

                            // stop the message center first
                            MessageCenterService.stop(getApplicationContext());

                            AccountManagerCallback<Boolean> callback = new AccountManagerCallback<Boolean>() {
                                public void run(AccountManagerFuture<Boolean> future) {
                                    // dismiss progress
                                    progress.dismiss();
                                    // exit now
                                    finish();
                                }
                            };
                            Authenticator.removeDefaultAccount(PreferencesActivity.this, callback);
                        }
                    })
                    .show();

                return true;
            }
        });

        // use custom background
        final Preference customBg = findPreference("pref_custom_background");
        customBg.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                // discard reference to custom background drawable
                Preferences.setCachedCustomBackground(null);
                return false;
            }
        });

        // set background
        final Preference setBackground = findPreference("pref_background_uri");
        setBackground.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQUEST_PICK_BACKGROUND);
                return true;
            }
        });

        // set balloon theme
        final Preference balloons = findPreference("pref_balloons");
        balloons.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Preferences.setCachedBalloonTheme((String) newValue);
                return true;
            }
        });

        // manual server address is handled in Application context
        // we just handle validation here
        setupPreferences(this);

        // server list last update timestamp
        final Preference updateServerList = findPreference("pref_update_server_list");
        updateServerList.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final ServerListUpdater updater = new ServerListUpdater(PreferencesActivity.this);

                final ProgressDialog diag = new ProgressDialog(PreferencesActivity.this);
                diag.setCancelable(true);
                diag.setMessage(getString(R.string.serverlist_updating));
                diag.setIndeterminate(true);
                diag.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        updater.cancel();
                    }
                });

                updater.setListener(new ServerListUpdater.UpdaterListener() {
                    @Override
                    public void error(Throwable e) {
                        diag.cancel();
                        message(R.string.serverlist_update_error);
                    }

                    @Override
                    public void networkNotAvailable() {
                        diag.cancel();
                        message(R.string.serverlist_update_nonetwork);
                    }

                    @Override
                    public void offlineModeEnabled() {
                        diag.cancel();
                        message(R.string.serverlist_update_offline);
                    }

                    @Override
                    public void noData() {
                        diag.cancel();
                        message(R.string.serverlist_update_nodata);
                    }

                    @Override
                    public void updated(final ServerList list) {
                        diag.dismiss();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Preferences.updateServerListLastUpdate(updateServerList, list);
                                // restart message center
                                MessageCenterService.restart(getApplicationContext());
                            }
                        });
                    }

                    private void message(final int textId) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(PreferencesActivity.this, textId,
                                    Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });

                diag.show();
                updater.start();
                return true;
            }
        });

        // update 'last update' string
        ServerList list = ServerListUpdater.getCurrentList(this);
        if (list != null)
            Preferences.updateServerListLastUpdate(updateServerList, list);
    }

    private interface OnPassphraseChangedListener {
        public void onPassphraseChanged(String passphrase);
    }

    private interface OnPassphraseRequestListener {
        public void onValidPassphrase(String passphrase);
        public void onInvalidPassphrase();
    }

    private void askCurrentPassphrase(final OnPassphraseRequestListener action) {
        new InputDialog.Builder(PreferencesActivity.this,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .setTitle(R.string.title_passphrase)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {

                    String passphrase = InputDialog.getInputText((Dialog) dialog).toString();
                    // user-entered passphrase is hashed, so compare with SHA-1 version
                    String hashed = MessageUtils.sha1(passphrase);
                    if (hashed.equals(((Kontalk) getApplicationContext())
                        .getCachedPassphrase())) {
                        action.onValidPassphrase(passphrase);
                    }
                    else {
                        action.onInvalidPassphrase();
                    }

                }
            })
            .show();
    }

    private void askNewPassphrase() {
        askNewPassphrase(null);
    }

    private void askNewPassphrase(final OnPassphraseChangedListener action) {
        new PasswordInputDialog.Builder(this)
            .setMinLength(PersonalKey.MIN_PASSPHRASE_LENGTH)
            .setTitle(R.string.pref_change_passphrase)
            .setPositiveButton(android.R.string.ok, new PasswordInputDialog.OnPasswordInputListener() {
                public void onClick(DialogInterface dialog, int which, String password) {
                    String oldPassword = ((Kontalk) getApplicationContext()).getCachedPassphrase();
                    try {
                        // user-entered passphrase must be hashed
                        String hashed = MessageUtils.sha1(password);
                        Authenticator.changePassphrase(PreferencesActivity.this, oldPassword, hashed, true);
                        ((Kontalk) getApplicationContext()).invalidatePersonalKey();

                        if (action != null)
                            action.onPassphraseChanged(password);
                    }
                    catch (Exception e) {
                        Toast.makeText(PreferencesActivity.this,
                            R.string.err_change_passphrase, Toast.LENGTH_LONG)
                            .show();
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void setupActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            ActionBar bar = getActionBar();
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                startActivity(new Intent(this, ConversationList.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_BACKGROUND) {
            if (resultCode == RESULT_OK) {
                // invalidate any previous reference
                Preferences.setCachedCustomBackground(null);
                // resize and cache image
                // TODO do this in background (might take some time)
                File image = Preferences.cacheConversationBackground(this, data.getData());
                // save to preferences
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                    .putString("pref_background_uri", Uri.fromFile(image).toString())
                    .commit();
            }
        }
        else
            super.onActivityResult(requestCode, resultCode, data);
    }

    public static void start(Activity context) {
        Intent intent = new Intent(context, PreferencesActivity.class);
        context.startActivityIfNeeded(intent, -1);
    }

    static void setupPreferences(final PreferenceActivity activity) {
        // disable push notifications if GCM is not available on the device
        if (!PushServiceManager.getInstance(activity).isServiceAvailable()) {
            final CheckBoxPreference push = (CheckBoxPreference) activity
                .findPreference("pref_push_notifications");
            push.setEnabled(false);
            push.setChecked(false);
            push.setSummary(R.string.pref_title_disabled_push_notifications);
        }

        final Preference manualServer = activity.findPreference("pref_network_uri");
        manualServer.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String value = newValue.toString().trim();
                if (value.length() > 0 && !EndpointServer.validate(value)) {
                    new AlertDialog.Builder(activity)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.pref_network_uri)
                        .setMessage(R.string.err_server_invalid_format)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return false;
                }
                return true;
            }
        });

    }

}

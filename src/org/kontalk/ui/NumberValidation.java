/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

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

import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.NumberValidator;
import org.kontalk.client.Protocol;
import org.kontalk.client.NumberValidator.NumberValidatorListener;
import org.kontalk.service.MessageCenterService;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnCancelListener;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class NumberValidation extends AccountAuthenticatorActivity
        implements NumberValidatorListener {
    private static final String TAG = NumberValidation.class.getSimpleName();

    public static final String ACTION_LOGIN = "org.kontalk.sync.LOGIN";

    public static final String PARAM_AUTHTOKEN_TYPE = "authtokenType";
    public static final String PARAM_CONFIRMCREDENTIALS = "confirmCredentials";
    public static final String PARAM_PHONENUMBER = "phoneNumber";

    private AccountManager mAccountManager;
    private EditText mPhone;
    private Button mValidateButton;
    private Button mManualButton;
    private ProgressDialog mProgress;
    private NumberValidator mValidator;

    private String mAuthtoken;
    private String mAuthtokenType;
    private String mPhoneNumber;
    private boolean mManualValidation;

    /**
     * If set we are just checking that the user knows their credentials; this
     * doesn't cause the user's password to be changed on the device.
     */
    private Boolean mConfirmCredentials = false;

    /** Was the original caller asking for an entirely new account? */
    protected boolean mRequestNewAccount = false;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.number_validation);

        mAccountManager = AccountManager.get(this);

        final Intent intent = getIntent();
        mPhoneNumber = intent.getStringExtra(PARAM_PHONENUMBER);
        mAuthtokenType = intent.getStringExtra(PARAM_AUTHTOKEN_TYPE);
        mRequestNewAccount = (mPhoneNumber == null);
        mConfirmCredentials =
            intent.getBooleanExtra(PARAM_CONFIRMCREDENTIALS, false);

        mPhone = (EditText) findViewById(R.id.phone_number);
        mValidateButton = (Button) findViewById(R.id.button_validate);
        mManualButton = (Button) findViewById(R.id.button_manual);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.number_validation_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_settings: {
                Intent intent = new Intent(this, BootstrapPreferences.class);
                startActivityIfNeeded(intent, -1);
                break;
            }
            default:
                return true;
        }
        return false;
    }

    /** Starts the validation activity. */
    public static void startValidation(Context context) {
        context.startActivity(new Intent(context, NumberValidation.class));
    }

    private void enableControls(boolean enabled) {
        mValidateButton.setEnabled(enabled);
        mManualButton.setEnabled(enabled);
        mPhone.setEnabled(enabled);
    }

    private void startValidation() {
        enableControls(false);

        // check number input
        String phone = null;
        try {
            phone = NumberValidator
                .fixNumber(this, mPhone.getText().toString());
        }
        catch (IllegalArgumentException e ) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.title_invalid_number)
                .setMessage(R.string.msg_invalid_number)
                .setNeutralButton(android.R.string.ok, null)
                .create().show();
            enableControls(true);
            return;
        }
        // exposing sensitive data - Log.d(TAG, "checking phone number: \"" + phone + "\"");

        // empty number :S
        if (phone.length() == 0) {
            phone = null;
        }

        // check phone number format
        if (phone != null) {
            if (!PhoneNumberUtils.isWellFormedSmsAddress(phone)) {
                Log.i(TAG, "not a well formed SMS address");
                phone = null;
            }
        }

        // phone is null - invalid number
        if (phone == null) {
            Toast.makeText(this, R.string.warn_invalid_number, Toast.LENGTH_SHORT)
                .show();
            enableControls(true);
            return;
        }

        mPhoneNumber = phone;

        // start async request
        Log.d(TAG, "phone number checked, sending validation request");
        startProgress();

        EndpointServer server = MessagingPreferences.getEndpointServer(this);
        mValidator = new NumberValidator(this, server, phone, mManualValidation);
        mValidator.setListener(this);
        mValidator.start();
    }

    /**
     * Opens the manual validation window for manual input of the validation code.
     * Also used by the view definition as the {@link OnClickListener}.
     * @param v not used
     */
    public void validateManual(View v) {
        // we are starting a manual validation
        mManualValidation = true;
        startValidation();
    }

    /**
     * Begins validation of the phone number.
     * Also used by the view definition as the {@link OnClickListener}.
     * @param v not used
     */
    public void validatePhone(View v) {
        // we are starting an automatic validation
        mManualValidation = false;
        startValidation();
    }

    /** No search here. */
    @Override
    public boolean onSearchRequested() {
        return false;
    }

    public void startProgress() {
        if (mProgress == null) {
            mProgress = new ProgressDialog(this);
            mProgress.setIndeterminate(true);
            mProgress.setCanceledOnTouchOutside(false);
            mProgress.setMessage(getText(R.string.msg_validating_phone));
            mProgress.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    Toast.makeText(NumberValidation.this, R.string.msg_validation_canceled, Toast.LENGTH_LONG).show();
                    abort();
                }
            });
        }
        mProgress.show();
    }

    public void abortProgress() {
        if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
    }

    public void abort() {
        abort(false);
    }

    public void abort(boolean ending) {
        if (!ending) {
            enableControls(true);
        }

        abortProgress();
        if (mValidator != null) {
            mValidator.shutdown();
            mValidator = null;
        }
    }

    @Override
    public void onAuthTokenFailed(NumberValidator v, Protocol.Status reason) {
        Log.e(TAG, "authorization token request failed (" + reason + ")");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NumberValidation.this,
                        R.string.err_authentication_failed,
                        Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    protected void finishLogin(String token) {
        Log.v(TAG, "finishing login");
        final Account account = new Account(mPhoneNumber, Authenticator.ACCOUNT_TYPE);
        mAuthtoken = token;

        if (mRequestNewAccount) {
            // the password is actually the auth token
            mAccountManager.addAccountExplicitly(account, mAuthtoken, null);
            // Set contacts sync for this account.
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true);
        } else {
            // TODO what here??
        }

        // send back result
        final Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, mPhoneNumber);
        intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, Authenticator.ACCOUNT_TYPE);
        if (mAuthtokenType != null
            && mAuthtokenType.equals(Authenticator.AUTHTOKEN_TYPE)) {
            intent.putExtra(AccountManager.KEY_AUTHTOKEN, mAuthtoken);
        }
        setAccountAuthenticatorResult(intent.getExtras());
        setResult(RESULT_OK, intent);

        // ok, start message center
        MessageCenterService.startMessageCenter(getApplicationContext());
        // end this
        finish();
    }

    protected void finishConfirmCredentials(boolean result) {
        Log.v(TAG, "finishing confirm credentials");

        // the password is actually the auth token
        final Account account = new Account(mPhoneNumber, Authenticator.ACCOUNT_TYPE);
        mAccountManager.setPassword(account, mAuthtoken);

        final Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_BOOLEAN_RESULT, result);
        setAccountAuthenticatorResult(intent.getExtras());
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onAuthTokenReceived(NumberValidator v, final CharSequence token) {
        Log.d(TAG, "got authorization token!");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                abort(true);

                if (!mConfirmCredentials) {
                    Toast.makeText(NumberValidation.this, R.string.msg_authenticated, Toast.LENGTH_LONG).show();
                    finishLogin(token.toString());
                }
                else {
                    finishConfirmCredentials(true);
                }
            }
        });
    }

    @Override
    public void onError(NumberValidator v, Throwable e) {
        Log.e(TAG, "validation error.", e);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NumberValidation.this, R.string.err_validation_error, Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    @Override
    public void onValidationFailed(NumberValidator v, Protocol.Status reason) {
        Log.e(TAG, "phone number validation failed (" + reason + ")");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NumberValidation.this, R.string.err_validation_failed, Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    @Override
    public void onValidationRequested(NumberValidator v) {
        if (mManualValidation) {
            Log.d(TAG, "validation has been requested, requesting validation code to user");
            // close progress dialog
            abortProgress();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // open validation code input dialog
                    LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
                    final View view = inflater.inflate(R.layout.edittext_dialog, null);
                    final EditText txt = (EditText) view.findViewById(R.id.textinput);

                    AlertDialog.Builder builder = new AlertDialog.Builder(NumberValidation.this);
                    builder
                        .setTitle("Validation code")
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startProgress();
                                // send the code
                                if (mValidator != null) {
                                    // FIXME just trusting the user isn't safe enough
                                    mValidator.manualInput(txt.getText());
                                    mValidator.start();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        })
                        .setView(view)
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                abort();
                            }
                        });

                    final Dialog dialog = builder.create();
                    dialog.show();
                }
            });
        }
        else
            Log.d(TAG, "validation has been requested, waiting for SMS");
    }

    @Override
    public void onValidationCodeReceived(NumberValidator v, CharSequence code) {
        Log.d(TAG, "validation SMS received, restarting validator thread");
        // start again!
        if (mValidator != null)
            mValidator.start();
    }
}

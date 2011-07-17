package org.kontalk.ui;

import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.data.Contact;
import org.kontalk.data.Conversation;
import org.kontalk.provider.MessagesProvider;
import org.kontalk.service.MessageCenterService;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;


/**
 * The conversations list activity.
 * @author Daniele Ricci
 * @version 1.0
 */
public class ConversationList extends ListActivity {

    private static final String TAG = ConversationList.class.getSimpleName();

    private static final int THREAD_LIST_QUERY_TOKEN = 8720;

    private static final int REQUEST_AUTHENTICATE = 7720;
    private static final int REQUEST_CONTACT_PICKER = 7721;

    private ThreadListQueryHandler mQueryHandler;
    private ConversationListAdapter mListAdapter;

    private final ConversationListAdapter.OnContentChangedListener mContentChangedListener =
        new ConversationListAdapter.OnContentChangedListener() {
        public void onContentChanged(ConversationListAdapter adapter) {
            startQuery();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.conversation_list_screen);

        mQueryHandler = new ThreadListQueryHandler(getContentResolver());

        ListView listView = getListView();
        LayoutInflater inflater = LayoutInflater.from(this);
        ConversationListItem headerView = (ConversationListItem)
                inflater.inflate(R.layout.conversation_list_item, listView, false);
        headerView.bind(getString(R.string.new_message),
                getString(R.string.create_new_message));
        listView.addHeaderView(headerView, null, true);

        mListAdapter = new ConversationListAdapter(this, null);
        mListAdapter.setOnContentChangedListener(mContentChangedListener);
        setListAdapter(mListAdapter);

        registerForContextMenu(getListView());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.conversation_list_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean visible = (mListAdapter.getCount() > 0);
        MenuItem item;
        item = menu.findItem(R.id.menu_search);
        item.setEnabled(visible);
        item.setVisible(visible);
        item = menu.findItem(R.id.menu_delete_all);
        item.setEnabled(visible);
        item.setVisible(visible);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_compose:
                chooseContact();
                return true;

            case R.id.menu_search:
                // TODO search
                onSearchRequested();
                return true;

            case R.id.menu_delete_all:
                deleteAll();
                return true;

            case R.id.menu_settings: {
                Intent intent = new Intent(this, MessagingPreferences.class);
                startActivityIfNeeded(intent, -1);
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    private static final int MENU_OPEN_THREAD = 1;
    private static final int MENU_VIEW_CONTACT = 2;
    private static final int MENU_DELETE_THREAD = 3;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ConversationListItem vitem = (ConversationListItem) info.targetView;
        Conversation conv = vitem.getConversation();
        if (conv != null) {
            Contact contact = conv.getContact();
            String title;
            if (contact != null)
                title = contact.getName() != null ? contact.getName() : contact.getNumber();
            else
                title = conv.getRecipient();

            menu.setHeaderTitle(title);
            menu.add(Menu.NONE, MENU_OPEN_THREAD, MENU_OPEN_THREAD, R.string.view_conversation);
            if (contact != null)
                menu.add(Menu.NONE, MENU_VIEW_CONTACT, MENU_VIEW_CONTACT, R.string.view_contact);
            menu.add(Menu.NONE, MENU_DELETE_THREAD, MENU_DELETE_THREAD, R.string.delete_thread);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        ConversationListItem vitem = (ConversationListItem) info.targetView;
        Conversation conv = vitem.getConversation();

        switch (item.getItemId()) {
            case MENU_OPEN_THREAD:
                openConversation(conv);
                return true;

            case MENU_VIEW_CONTACT:
                Contact contact = conv.getContact();
                if (contact != null)
                    startActivity(new Intent(Intent.ACTION_VIEW, contact.getUri()));
                return true;

            case MENU_DELETE_THREAD:
                Log.i(TAG, "deleting thread: " + conv.getThreadId());
                deleteThread(conv.getThreadId());
                return true;
        }

        return super.onContextItemSelected(item);
    }

    private void deleteThread(final long threadId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_delete_thread);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setMessage(R.string.confirm_will_delete_thread);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                MessagesProvider.deleteThread(ConversationList.this, threadId);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.create().show();
    }

    private void chooseContact() {
        // TODO one day it will be like this
        // Intent i = new Intent(Intent.ACTION_PICK, Users.CONTENT_URI);
        Intent i = new Intent(this, ContactsListActivity.class);
        startActivityForResult(i, REQUEST_CONTACT_PICKER);
    }

    private void deleteAll() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_delete_all);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setMessage(R.string.confirm_will_delete_all);
        builder.setPositiveButton(android.R.string.ok, new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                MessagesProvider.deleteDatabase(ConversationList.this);
                MessagingNotification.updateMessagesNotification(getApplicationContext(), false);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.create().show();
    }

    private void startQuery() {
        try {
            setTitle(getString(R.string.refreshing));
            setProgressBarIndeterminateVisibility(true);

            Conversation.startQuery(mQueryHandler, THREAD_LIST_QUERY_TOKEN);
        } catch (SQLiteException e) {
            Log.e(TAG, "query error", e);
        }
    }

    /* generated from +393271582382
    private static final String testToken =
        "owGbwMvMwCGYeiJtndUThX+Mp6OSmHXVLH1v/7yaam6cmmiclGpknGJg" +
        "YmJpammeaGGUbGaUmmJpYZhmamGSaJpsaGGYVGNsbOJi4GZm7GZsZm7kaGD" +
        "qZmloZmLhamFm6mxhZuboauzoamJk4ObaEcfCIMjBwMbKBDKegYtTAGbtrd" +
        "UM/13clz3N8TknL2M/mXkXz9NXm1YyubvEqvfHsxjdXHVK2YiRYWtq+vYTn" +
        "YbJ/js6NMQOdj1tDbpXU8Dy4fn1jNvM8fNFHQA=";
     */

    @Override
    protected void onStart() {
        super.onStart();
        startQuery();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (Authenticator.getDefaultAccount(this) == null) {
            startActivityForResult(new Intent(this, NumberValidation.class), REQUEST_AUTHENTICATE);
            // finish for now...
            return;
        }

        // check if contacts list has already been checked
        if (!MessagingPreferences.getContactsChecked(this)) {
            // TODO start the contacts list checker thread
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // authentication
        if (requestCode == REQUEST_AUTHENTICATE) {
            // ok, start message center
            if (resultCode == RESULT_OK)
                MessageCenterService.startMessageCenter(this);
            // failed - exit
            else
                finish();
        }

        // contact chooser
        else if (requestCode == REQUEST_CONTACT_PICKER) {
            if (resultCode == RESULT_OK) {
                Uri contact = Contacts.lookupContact(getContentResolver(), data.getData());
                if (contact != null) {
                    Log.i(TAG, "composing message for contact: " + contact);
                    Intent i = ComposeMessage.fromContactPicker(this, contact);
                    if (i != null)
                        startActivity(i);
                    else
                        Toast.makeText(this, "Contact seems not to be registered on Kontalk.", Toast.LENGTH_LONG)
                            .show();
                }
            }
        }
    }

    /**
     * Called when a new intent is sent to the activity (if already started).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        startQuery();
    }

    /**
     * Prevents the list adapter from using the cursor (which is being destroyed).
     */
    @Override
    protected void onStop() {
        super.onStop();
        mListAdapter.changeCursor(null);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        ConversationListItem cv = (ConversationListItem) v;
        Conversation conv = cv.getConversation();
        if (conv != null) {
            openConversation(conv);
        }

        // new composer
        else {
            chooseContact();
        }
    }

    private void openConversation(Conversation conv) {
        Intent intent = new Intent(this, ComposeMessage.class);
        intent.putExtra(ComposeMessage.MESSAGE_THREAD_ID, conv.getThreadId());
        intent.putExtra(ComposeMessage.MESSAGE_THREAD_PEER, conv.getRecipient());
        Contact contact = conv.getContact();
        if (contact != null) {
            intent.putExtra(ComposeMessage.MESSAGE_THREAD_USERNAME, contact.getName());
            intent.putExtra(ComposeMessage.MESSAGE_THREAD_USERPHONE, contact.getNumber());
        }
        startActivity(intent);
    }

    /**
     * The conversation list query handler.
     */
    private final class ThreadListQueryHandler extends AsyncQueryHandler {
        public ThreadListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
            case THREAD_LIST_QUERY_TOKEN:
                mListAdapter.changeCursor(cursor);
                setTitle(getString(R.string.app_name));
                setProgressBarIndeterminateVisibility(false);
                break;

            default:
                Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }
        }
    }
}
package nl.tudelft.cs4160.trustchain_android.main;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;

import nl.tudelft.cs4160.trustchain_android.ChainExplorerActivity;
import nl.tudelft.cs4160.trustchain_android.KeyActivity;
import nl.tudelft.cs4160.trustchain_android.R;
import nl.tudelft.cs4160.trustchain_android.Util.Key;
import nl.tudelft.cs4160.trustchain_android.block.BlockProto;
import nl.tudelft.cs4160.trustchain_android.block.TrustChainBlock;
import nl.tudelft.cs4160.trustchain_android.database.TrustChainDBContract;
import nl.tudelft.cs4160.trustchain_android.database.TrustChainDBHelper;

import static nl.tudelft.cs4160.trustchain_android.block.TrustChainBlock.createTestBlock;

public class MainActivity extends AppCompatActivity {

    private final static String TAG = MainActivity.class.toString();

    BlockProto.TrustChainBlock message;
    TrustChainDBHelper dbHelper;
    SQLiteDatabase db;

    TextView externalIPText;
    TextView localIPText;
    TextView statusText;
    Button connectionButton;
    Button chainExplorerButton;
    Button keyOptionsButton;
    EditText editTextDestinationIP;
    EditText editTextDestinationPort;

    MainActivity thisActivity;

    /**
     * Key pair of user
     */
    KeyPair kp;

    /**
     * Listener for the connection button.
     * On click a message is sent to the connected device.
     */
    View.OnClickListener connectionButtonListener = new View.OnClickListener(){
        @Override
        public void onClick(View view) {
            ClientTask task = new ClientTask(
                    editTextDestinationIP.getText().toString(),
                    Integer.parseInt(editTextDestinationPort.getText().toString()),
                    message,
                    thisActivity);
            task.execute();
            //TODO: for testing purposes, block insertion in DB must be done in another place
            dbHelper.insertInDB(createTestBlock(), db);
        }
    };

    View.OnClickListener chainExplorerButtonListener = new View.OnClickListener(){
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(thisActivity, ChainExplorerActivity.class);
            startActivity(intent);
        }
    };

    View.OnClickListener keyOptionsListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(thisActivity, KeyActivity.class);
            startActivity(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initVariables();
        init();
    }

    private void initVariables() {
        thisActivity = this;
        localIPText = (TextView) findViewById(R.id.my_local_ip);
        externalIPText = (TextView) findViewById(R.id.my_external_ip);
        statusText = (TextView) findViewById(R.id.status);
        statusText.setMovementMethod(new ScrollingMovementMethod());
        editTextDestinationIP = (EditText) findViewById(R.id.destination_IP);
        editTextDestinationPort = (EditText) findViewById(R.id.destination_port);
        connectionButton = (Button) findViewById(R.id.connection_button);
        chainExplorerButton = (Button) findViewById(R.id.chain_explorer_button);
        keyOptionsButton = (Button) findViewById(R.id.key_options_button);
    }

    private void init() {
        dbHelper = new TrustChainDBHelper(thisActivity);
        db = dbHelper.getWritableDatabase();

        //create or load keys
        initKeys();

        if(isStartedFirstTime()) {
            message = TrustChainBlock.createGenesisBlock();
            dbHelper.insertInDB(message, db);
        }

        updateIP();
        updateLocalIPField(getLocalIPAddress());

        connectionButton.setOnClickListener(connectionButtonListener);
        chainExplorerButton.setOnClickListener(chainExplorerButtonListener);
        keyOptionsButton.setOnClickListener(keyOptionsListener);
        Server socketServer = new Server(thisActivity);
        socketServer.start();
    }

    private void initKeys() {
        kp = Key.loadKeys(getApplicationContext());
        if(kp == null) {
            kp = Key.createNewKeyPair();
            Key.saveKey(getApplicationContext(), Key.DEFAULT_PUB_KEY_FILE, kp.getPublic());
            Key.saveKey(getApplicationContext(), Key.DEFAULT_PRIV_KEY_FILE, kp.getPrivate());
            Log.i(TAG, "New keys created");
        }
    }

    /**
     * Checks if this is the first time the app is started and returns a boolean value indicating
     * this state.
     * @return state - false if the app has been initialized before, true if first time app started
     */
    public boolean isStartedFirstTime() {
        // check if a genesis block is present in database
        SQLiteDatabase dbReadable = dbHelper.getReadableDatabase();
        String[] projection = {
                TrustChainDBContract.BlockEntry.COLUMN_NAME_SEQUENCE_NUMBER,
        };

        String whereClause = TrustChainDBContract.BlockEntry.COLUMN_NAME_SEQUENCE_NUMBER + " = ?";
        String[] whereArgs = new String[] {Integer.toString(TrustChainBlock.GENESIS_SEQ)};

        Cursor cursor = dbReadable.query(
                TrustChainDBContract.BlockEntry.TABLE_NAME,     // Table name for the query
                projection,                                     // The columns to return
                whereClause,                                           // Filter for which rows to return
                whereArgs,                                           // Filter arguments
                null,                                           // Declares how to group rows
                null,                                           // Declares which row groups to include
                null                                           // How the rows should be ordered
        );
        if(cursor.getCount() == 1) {
            return false;
        }
        return true;

        // TODO: check if a keypair is already created - rico: I don't think this is the right place to check thsi
    }

    /**
     * Updates the external IP address textfield to the given IP address.
     */
    public void updateExternalIPField(String ipAddress) {
        externalIPText.setText(ipAddress);
        System.out.println("IP ADDRESS: " + ipAddress);
    }

    /**
     * Updates the internal IP address textfield to the given IP address.
     */
    public void updateLocalIPField(String ipAddress) {
        localIPText.setText(ipAddress);
        System.out.println("IP ADDRESS: " + ipAddress);
    }

    /**
     * Finds the external IP address of this device by making an API call to https://www.ipify.org/.
     * The networking runs on a separate thread.
     * @return a string representation of the device's external IP address
     */
    public void updateIP() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
                    final String ip = s.next();
                    // new thread to handle UI updates
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateExternalIPField(ip);
                        }
                    });
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    /**
     * Finds the local IP address of this device, loops trough network interfaces in order to find it.
     * The address that is not a loopback address is the IP of the device.
     * @return a string representation of the device's IP address
     */
    public String getLocalIPAddress() {
        try {
            List<NetworkInterface> netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface netInt : netInterfaces) {
                List<InetAddress> addresses = Collections.list(netInt.getInetAddresses());
                for (InetAddress addr : addresses) {
                    if(addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}

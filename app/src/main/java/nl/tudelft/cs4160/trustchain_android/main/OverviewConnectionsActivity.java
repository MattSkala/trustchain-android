package nl.tudelft.cs4160.trustchain_android.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import nl.tudelft.cs4160.trustchain_android.R;
import nl.tudelft.cs4160.trustchain_android.block.TrustChainBlockHelper;
import nl.tudelft.cs4160.trustchain_android.block.ValidationResult;
import nl.tudelft.cs4160.trustchain_android.chainExplorer.ChainExplorerActivity;
import nl.tudelft.cs4160.trustchain_android.crypto.DualSecret;
import nl.tudelft.cs4160.trustchain_android.crypto.Key;
import nl.tudelft.cs4160.trustchain_android.funds.FundsActivity;
import nl.tudelft.cs4160.trustchain_android.funds.qr.ExportWalletQRActivity;
import nl.tudelft.cs4160.trustchain_android.funds.qr.ScanQRActivity;
import nl.tudelft.cs4160.trustchain_android.inbox.InboxActivity;
import nl.tudelft.cs4160.trustchain_android.message.MessageProto;
import nl.tudelft.cs4160.trustchain_android.passport.ocr.camera.CameraActivity;

import nl.tudelft.cs4160.trustchain_android.network.Network;
import nl.tudelft.cs4160.trustchain_android.network.NetworkCommunicationListener;
import nl.tudelft.cs4160.trustchain_android.network.peer.Peer;
import nl.tudelft.cs4160.trustchain_android.network.peer.PeerHandler;
import nl.tudelft.cs4160.trustchain_android.network.peer.PeerListener;
import nl.tudelft.cs4160.trustchain_android.storage.database.TrustChainDBHelper;
import nl.tudelft.cs4160.trustchain_android.storage.sharedpreferences.BootstrapIPStorage;
import nl.tudelft.cs4160.trustchain_android.storage.sharedpreferences.UserNameStorage;

public class OverviewConnectionsActivity extends AppCompatActivity implements NetworkCommunicationListener, PeerListener {

    // The server ip address, this is the bootstrap phone that's always running
    public final static String CONNECTABLE_ADDRESS = "130.161.211.254";
    public final static int DEFAULT_PORT = 1873;
    private final static int BUFFER_SIZE = 65536;
    private PeerListAdapter activePeersAdapter;
    private PeerListAdapter newPeersAdapter;
    private TrustChainDBHelper dbHelper;
    private Network network;
    private PeerHandler peerHandler;
    private String wan = "";
    private static final String TAG = "OverviewConnections";

    /**
     * Initialize views, start send and receive threads if necessary.
     * Start a thread that refreshes the peers every second.
     *
     * @param savedInstanceState saved instance state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overview_connections);
        initVariables(savedInstanceState);
        initExitButton();
        addInitialPeer();
        startListenThread();
        startSendThread();
        initPeerLists();

        Runnable refreshTask = () -> {
            while(true) {
                updatePeerLists();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
        new Thread(refreshTask).start();
    }

    /**
     * Initialize all local variables
     * If this activity is opened with a saved instance state
     * we load the list of peers from this saved state.
     * @param savedInstanceState
     */
    private void initVariables(Bundle savedInstanceState) {
        peerHandler = new PeerHandler(UserNameStorage.getUserName(this));
        dbHelper = new TrustChainDBHelper(this);
        initKey();
        network = Network.getInstance(getApplicationContext());

        if (savedInstanceState != null) {
            ArrayList<Peer> list = (ArrayList<Peer>) savedInstanceState.getSerializable("peers");
            getPeerHandler().setPeerList(list);
        }

        getPeerHandler().setPeerListener(this);
        network.setNetworkCommunicationListener(this);
        network.updateConnectionType((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE));
        ((TextView) findViewById(R.id.peer_id)).setText(peerHandler.getHashId());
    }

    /**
     * If the app is launched for the first time
     * a new keyPair is created and saved locally in the storage.
     * A genesis block is also created automatically.
     */
    private void initKey() {
        DualSecret kp = Key.loadKeys(getApplicationContext());
        if (kp == null) {
            kp = Key.createAndSaveKeys(getApplicationContext());
            MessageProto.TrustChainBlock block = TrustChainBlockHelper.createGenesisBlock(kp);
            dbHelper.insertInDB(block);
        }
    }

    /**
     * Inflates the menu with a layout.
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    /**
     * Define what should be executed when one of the item in the menu is clicked.
     *
     * @param item the item in the menu.
     * @return true if everything was executed.- [ ] No out-of-sleep feature on Android. dead overlay.
- [ ] update on_packet() every second a screen refresh and update message-timeout values on screen.
- [ ] design and implement a fault-resilient overlay. make flawless.
- [ ] documented algorithm
- [ ] Add last send message + got last response message
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.chain_menu:
                Intent chainExplorerActivity = new Intent(this, ChainExplorerActivity.class);
                startActivity(chainExplorerActivity);
                return true;
            case R.id.connection_explanation_menu:
                Intent ConnectionExplanationActivity = new Intent(this, ConnectionExplanationActivity.class);
                startActivity(ConnectionExplanationActivity);
                return true;
            case R.id.import_tokens:
                startActivity(new Intent(OverviewConnectionsActivity.this, ScanQRActivity.class));
                return true;
            case R.id.export_tokens:
                startActivity(new Intent(OverviewConnectionsActivity.this, ExportWalletQRActivity.class));
                return true;
            case R.id.funds:
                startActivity(new Intent(this, FundsActivity.class));
                return true;
            case R.id.find_peer:
                Intent bootstrapActivity = new Intent(this, ChangeBootstrapActivity.class);
                startActivityForResult(bootstrapActivity, 1);
                return true;
            case R.id.passport_scan:
                Intent cameraActivity = new Intent(this, CameraActivity.class);
                startActivityForResult(cameraActivity, 1);
                return true;
            default:
                return false;
        }
    }

    /**
     * On click open inbox button open the inbox activity.
     * @param view
     */
    public void onClickOpenInbox(View view) {
        InboxActivity.peerList = peerHandler.getPeerList();
        Intent inboxActivityIntent = new Intent(this, InboxActivity.class);
        startActivity(inboxActivityIntent);
    }

    /**
     * Initialize the exit button.
     */
    private void initExitButton() {
        Button mExitButton = findViewById(R.id.exit_button);
        mExitButton.setOnClickListener(view -> finish());
    }

    /**
     * Initialize the inboxItem lists.
     */
    private void initPeerLists() {
        ListView connectedPeerConnectionListView = findViewById(R.id.active_peers_list_view);
        ListView incomingPeerConnectionListView = findViewById(R.id.new_peers_list_view);
        CoordinatorLayout content = findViewById(R.id.content);
        activePeersAdapter = new PeerListAdapter(getApplicationContext(), R.layout.item_peer_connection_list, peerHandler.getactivePeersList(), content);
        connectedPeerConnectionListView.setAdapter(activePeersAdapter);
        newPeersAdapter = new PeerListAdapter(getApplicationContext(), R.layout.item_peer_connection_list, peerHandler.getnewPeersList(), content);
        incomingPeerConnectionListView.setAdapter(newPeersAdapter);
    }


    /**
     * This method is the callback when submitting the new bootstrap address.
     * The method is called when leaving the ChangeBootstrapActivity.
     * The filled in ip address is passed on to this method.
     * When the callback of the bootstrap activity is successful
     * set this ip address as ConnectableAddress in the preferences.
     *
     * @param requestCode
     * @param resultCode
     * @param data the data passed on by the previous activity, in this case the ip address
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("ConnectableAddress", data.getStringExtra("ConnectableAddress"));
                editor.apply();
                addInitialPeer();
            }
        }
    }

    /**
     *
     * NETWORKING STUFF
     *
     */

    /**
     * Add the initial hard-coded connectable inboxItem to the inboxItem list.
     */
    public void addInitialPeer() {
        String address = BootstrapIPStorage.getIP(this);
        CreateInetSocketAddressTask createInetSocketAddressTask = new CreateInetSocketAddressTask(this);
        try {
            if (address != null && !address.equals("")) {
                createInetSocketAddressTask.execute(address, String.valueOf(DEFAULT_PORT));
            } else {
                createInetSocketAddressTask.execute(CONNECTABLE_ADDRESS, String.valueOf(DEFAULT_PORT));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Asynctask to create the inetsocketaddress since network stuff can no longer happen on the main thread in android v3 (honeycomb).
     */
    private static class CreateInetSocketAddressTask extends AsyncTask<String, Void, InetSocketAddress> {
        private WeakReference<OverviewConnectionsActivity> activityReference;

        CreateInetSocketAddressTask(OverviewConnectionsActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected InetSocketAddress doInBackground(String... params) {
            InetSocketAddress inetSocketAddress = null;
            OverviewConnectionsActivity activity = activityReference.get();
            if (activity == null) return null;

            try {
                InetAddress connectableAddress = InetAddress.getByName(params[0]);
                int port = Integer.parseInt(params[1]);
                inetSocketAddress = new InetSocketAddress(connectableAddress, port);

                activity.peerHandler.addPeer(null, inetSocketAddress);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

            return inetSocketAddress;
        }
    }

    /**
     * Start the thread send thread responsible for sending an introduction request to 10 random peers every 5 seconds as a heartbeat timer.
     * This number is chosen arbitrarily to avoid the app sending too much packets and using too much data keeping connections open with many peers.
     */
    private void startSendThread() {
        Thread sendThread = new Thread(() -> {
            boolean networkUnreachable = false;
            View view = findViewById(android.R.id.content);
            Snackbar networkUnreachableSnackbar = Snackbar.make(view, "Network unavailable", Snackbar.LENGTH_INDEFINITE);

            while(true) {
                try {
                    if (peerHandler.size() > 0) {
                        // select 10 random peers to send an introduction request to
                        int limit = 10;
                        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
                        lock.readLock().lock();
                        List<Peer> connectedPeers = new ArrayList<>(peerHandler.getPeerList());
                        lock.readLock().unlock();
                        if(connectedPeers.size() <= limit) {
                            for(Peer peer : connectedPeers){
                                network.sendIntroductionRequest(peer);
                            }
                        } else {
                            Random rand = new Random();
                            for (int i = 0; i < limit; i++) {
                                int index = rand.nextInt(connectedPeers.size());
                                network.sendIntroductionRequest(connectedPeers.get(index));
                                connectedPeers.remove(index);
                            }
                        }
                    }
                    // if the network is reachable again, remove the snackbar
                    if(networkUnreachable) {
                        networkUnreachableSnackbar.dismiss();
                    }
                } catch (SocketException e) {
                    networkUnreachable = true;
                    networkUnreachableSnackbar.show();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        sendThread.start();
        Log.d(TAG, "Send thread started");
    }

    /**
     * Start the listen thread. The thread opens a new {@link DatagramChannel} and calls {@link Network#dataReceived(Context, ByteBuffer,
     * InetSocketAddress)} for each incoming datagram.
     */
    private void startListenThread() {
        final Context context = this;

        Thread listenThread = new Thread(() -> {
            try {
                ByteBuffer inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
                while (!Thread.interrupted()) {
                    inputBuffer.clear();
                    SocketAddress address = network.receive(inputBuffer);
                    inputBuffer.flip();
                    network.dataReceived(context, inputBuffer, (InetSocketAddress) address);
                }
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "Listen thread stopped");
            }
        });
        listenThread.start();
        Log.d(TAG, "Listen thread started");
    }

    /**
     * Update wan address
     * @param message a message that was received, the destination is our wan address
     */
    public void updateWan(MessageProto.Message message) throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(message.getDestinationAddress().toByteArray());
        int port = message.getDestinationPort();
        InetSocketAddress socketAddress = new InetSocketAddress(addr, port);

        if (peerHandler.getWanVote().vote(socketAddress)) {
            wan = peerHandler.getWanVote().getAddress().toString();
        }
        setWanvote(wan.replace("/",""));
    }

    /**
     * Set the external ip field based on the WAN vote.
     *
     * @param ip the ip address.
     */
    private void setWanvote(final String ip) {
        new Handler(getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                TextView mWanVote = findViewById(R.id.wanvote);
                mWanVote.setText(ip);
            }
        });
    }

    /**
     * Handle an introduction request. Send a puncture request to the included invitee.
     *
     * @param peer    the orimessagegin inboxItem.
     * @param request the message.
     * @throws IOException
     */
    @Override
    public void handleIntroductionRequest(Peer peer, MessageProto.IntroductionRequest request) throws IOException {
        peer.setConnectionType((int) request.getConnectionType());
        if (getPeerHandler().size() > 1) {
            Peer invitee = getPeerHandler().getEligiblePeer(peer);
            if (invitee != null) {
                network.sendIntroductionResponse(peer, invitee);
                network.sendPunctureRequest(invitee, peer);
                Log.d("Network", "Introducing " + invitee.getAddress() + " to " + peer.getAddress());
            }
        } else {
            Log.d("Network", "Peerlist too small, can't handle introduction request");
            network.sendIntroductionResponse(peer, null);
        }
    }

    /**
     * Handle an introduction response. Parse incoming PEX peers.
     *
     * @param peer    the peer that sent this response.
     * @param response the message.
     */
    @Override
    public void handleIntroductionResponse(Peer peer, MessageProto.IntroductionResponse response) throws Exception {
        peer.setConnectionType((int) response.getConnectionType());
        List<ByteString> pex = response.getPexList();
        for (ByteString pexPeer : pex) {
            Peer p = Peer.deserialize(pexPeer.toByteArray());
            Log.d(TAG, "From " + peer + " | found peer in pexList: " + p);

            if (!getPeerHandler().hashId.equals(p.getPeerId())) {
                getPeerHandler().getOrMakePeer(p.getPeerId(), p.getAddress());
            }
        }
    }

    /**
     * Handle a puncture. Does nothing because the only purpose of a puncture is to punch a hole in the NAT.
     *
     * @param peer    the origin inboxItem.
     * @param puncture the message.
     * @throws IOException
     */
    @Override
    public void handlePuncture(Peer peer, MessageProto.Puncture puncture) throws IOException {
    }

    /**
     * Handle a puncture request. Sends a puncture to the puncture inboxItem included in the message.
     *
     * @param peer    the origin inboxItem.
     * @param request the message.
     * @throws IOException
     */
    @Override
    public void handlePunctureRequest(Peer peer, MessageProto.PunctureRequest request) throws IOException {
        Peer puncturePeer = null;
        try {
            puncturePeer = Peer.deserialize(request.getPuncturePeer().toByteArray());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        if (!getPeerHandler().peerExistsInList(puncturePeer)) {
            network.sendPuncture(puncturePeer);
        }
    }

    /**
     * Handle the received (half) block.
     * This block is placed in in the TrustChainDB, except if it is INVALID.
     * @param peer the sending peer
     * @param block the data send
     * @throws IOException
     */
    @Override
    public void handleReceivedBlock(Peer peer, MessageProto.TrustChainBlock block) {
        try {
            if (TrustChainBlockHelper.validate(block,dbHelper).getStatus() != ValidationResult.INVALID ) {
                dbHelper.replaceInDB(block);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Handle crawl request
     * @param peer the sending peer
     * @param request the crawlRequest
     * @throws IOException
     */
    @Override
    public void handleCrawlRequest(Peer peer, MessageProto.CrawlRequest request) throws IOException {
        //ToDo for future application sending the entire chain is a bit too much
        for (MessageProto.TrustChainBlock block : dbHelper.getAllBlocks()) {
            network.sendBlockMessage(peer, block);
        }
    }

    /**
     * Update the showed inboxItem lists.
     * First split into new peers and the active list
     * Then remove the peers that aren't responding for a long time.
     */
    @Override
    public void updatePeerLists() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    peerHandler.splitPeerList();
                    peerHandler.removeDeadPeers();
                    activePeersAdapter.notifyDataSetChanged();
                    newPeersAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    /**
     * When the app closes destroy the network channel.
     */
    @Override
    protected void onDestroy() {
        network.closeChannel();
        super.onDestroy();
    }

    /**
     * when loading the activity from instance state add
     * the peer list as serializable.
     * @param outState
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putSerializable("peers", peerHandler.getPeerList());
        super.onSaveInstanceState(outState);
    }

    /**
     * Display connectionType
     *
     * @param connectionType
     * @param typename
     * @param subtypename
     */
    @Override
    public void updateConnectionType(int connectionType, String typename, String subtypename) {
        String connectionTypeStr = typename + " " + subtypename;
        ((TextView) findViewById(R.id.connection_type)).setText(connectionTypeStr);
    }

    /**
     * Update the source address textview
     * @param address
     */
    @Override
    public void updateInternalSourceAddress(final String address) {
        Log.d("App-To-App Log", "Local ip: " + address);

        runOnUiThread(() -> {
            TextView localIp = findViewById(R.id.local_ip_address_view);
            localIp.setText(address);
        });
    }

    /**
     * Update the connected peer adapter by notifying that the data has changed.
     */
    @Override
    public void updateActivePeers() {
        activePeersAdapter.notifyDataSetChanged();
    }

    /**
     * Update the incoming peer adapter by notifying that the data has changed. Usually when a new
     * peer has been found that we are not connected to yet.
     */
    @Override
    public void updateNewPeers() {
        newPeersAdapter.notifyDataSetChanged();
    }

    /**
     * Return the peer handler object.
     * @return
     */
    @Override
    public PeerHandler getPeerHandler() {
        return peerHandler;
    }
}
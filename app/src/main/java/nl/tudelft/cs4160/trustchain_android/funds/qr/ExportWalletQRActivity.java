package nl.tudelft.cs4160.trustchain_android.funds.qr;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import nl.tudelft.cs4160.trustchain_android.R;
import nl.tudelft.cs4160.trustchain_android.block.TrustChainBlockHelper;
import nl.tudelft.cs4160.trustchain_android.crypto.DualSecret;
import nl.tudelft.cs4160.trustchain_android.crypto.Key;
import nl.tudelft.cs4160.trustchain_android.funds.qr.models.QRBlock;
import nl.tudelft.cs4160.trustchain_android.funds.qr.models.QRTransaction;
import nl.tudelft.cs4160.trustchain_android.funds.qr.models.QRWallet;
import nl.tudelft.cs4160.trustchain_android.message.MessageProto;
import nl.tudelft.cs4160.trustchain_android.storage.database.TrustChainDBHelper;


public class ExportWalletQRActivity extends AppCompatActivity {
    final static String TAG = "ExportWalletQRActivity";
    ImageView imageView;
    ProgressBar progressBar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_wallet_qr);
        imageView = findViewById(R.id.qr_image);
        progressBar = findViewById(R.id.qr_progress);

        new Thread(new Runnable() {
            @Override
            public void run() {
                exportQRCode();
            }
        }).start();
    }

    private void exportQRCode() {
        try {
            Moshi moshi = new Moshi.Builder().build();
            JsonAdapter<QRWallet> walletAdapter = moshi.adapter(QRWallet.class);
            TrustChainDBHelper dbHelper = new TrustChainDBHelper(this);

            // Step 1: Create a temporary new identity (we call this C)
            DualSecret keyPairOfC = Key.createNewKeyPair();
            DualSecret keyPairOfA = Key.loadKeys(this);

            // Step 2: Transfer the funds from our current wallet (A) to (C).
            // - * Create Partially Signed Block from A
            // - * Create Partially Signed Block from C

            // Partly duplicated code from Communication.java
            QRTransaction transaction = new QRTransaction();
            try {
                MessageProto.TrustChainBlock lastBlock = dbHelper.getLatestBlock(keyPairOfA.getPublicKeyPair().toBytes());
                JSONObject object = new JSONObject(lastBlock.getTransaction().getUnformatted().toStringUtf8());
                System.out.println(object.toString());
                // Pretend that some transfer identity
                // uploaded to you by the same amount that you uploaded to others.
                // Effectilfy transfering reputation.
                long total_up =  object.getLong("total_up");
                long total_down =  object.getLong("total_down");

                if (total_down >= total_up ){
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            displayError("You do not have tokens to transfer");
                        }
                    });
                    return;

                }
                transaction.down = 0 ;
                transaction.up = total_up - total_down;
                transaction.totalUp = total_up;
                transaction.totalDown = total_up;
                //transaction.totalUp = object.getLong("total_up") + transaction.down;
                //transaction.totalDown = object.getLong("total_down") + transaction.up;
            } catch (Exception e) {
                Log.e(TAG, "Could not export QR code, chain data might be corrupted: " + e.getMessage(), e);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        displayError("Cannot export QR code, there are no funds in your chain data!");
                    }
                });
                return;
            }

            JsonAdapter<QRTransaction> transactionAdapter = moshi.adapter(QRTransaction.class);
            MessageProto.TrustChainBlock blockAtoC =
                    TrustChainBlockHelper.createBlock(
                            transactionAdapter.toJson(transaction).getBytes(),
                            null,
                            dbHelper,
                            keyPairOfA.getPublicKeyPair().toBytes(),
                            null,
                            keyPairOfC.getPublicKeyPair().toBytes()
                    );
            blockAtoC = TrustChainBlockHelper.sign(blockAtoC, keyPairOfA.getSigningKey());

            MessageProto.TrustChainBlock blockCtoA =
                    TrustChainBlockHelper.createBlock(
                            transactionAdapter.toJson(transaction).getBytes(),
                            null,
                            dbHelper,
                            keyPairOfC.getPublicKeyPair().toBytes(),
                            blockAtoC,
                            keyPairOfA.getPublicKeyPair().toBytes()
                    );
            blockCtoA = TrustChainBlockHelper.sign(blockCtoA, keyPairOfC.getSigningKey());

            dbHelper.insertInDB(blockAtoC);
            dbHelper.insertInDB(blockCtoA);


            // Step 3: Construct data to put in QR code, so the receiver can construct
            // Both C -> B and B -> C Blocks
            // - * Create Partially Signed Block from C -> B

            QRBlock block = new QRBlock();
            block.blockHashBase64 = Integer.toHexString(blockCtoA.hashCode());
            block.sequenceNumber = blockCtoA.getSequenceNumber();

            // Put everything in a wallet
            QRWallet wallet = new QRWallet();
            wallet.privateKeyBase64 = Base64.encodeToString(getBinaryExportKey(keyPairOfC),Base64.DEFAULT);
            wallet.block = block;
            wallet.transaction = transaction;

            String jsonEncoded = walletAdapter.toJson(wallet);

            // Step 4: Display QR code with data
            System.out.println("Encoding " + jsonEncoded + " as QR code!");
            MultiFormatWriter writer = new MultiFormatWriter();
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            int size = metrics.widthPixels;
            BitMatrix matrix = writer.encode(jsonEncoded, BarcodeFormat.QR_CODE, size, size);

            final Bitmap image = QRGenerator.GenerateQRCode(size, matrix);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    displayQRCode(image);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Could not export QR code:", e);
            final String msg = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    displayError("Export Failed: " + msg);
                }
            });
        }
    }

    public byte[] getBinaryExportKey(DualSecret pk) throws IOException {
        ByteArrayOutputStream export = new ByteArrayOutputStream( );
        export.write("LibNaCLSK:".getBytes());
        export.write(pk.getPrivateKey().toBytes());
        export.write(pk.getSignSeed());

        return export.toByteArray();
    }

    private void displayQRCode(Bitmap bitmap) {
        progressBar.setVisibility(View.GONE);
        imageView.setImageBitmap(bitmap);
    }

    private void displayError(String error) {
        new AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(error)
                .setNeutralButton(android.R.string.ok, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        finish();
                    }
                })
                .show();
    }
}

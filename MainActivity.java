package com.gg.myapplication5.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;

public class MainActivity extends Activity {

    public ImageView itTeacher;
    public ImageView enTeacher;
    private TextView loginErrorMsg;
    public int idLezione;
    public int indexcue=0;
    public int indexcuetext=0;
    JSONArray jArray;
    public int nextCueChange;
    Handler handler;

    Button fermaLezione;
    Button cominciaLezione;
    Button vaialquiz;
    TextView testoItaliano;
    TextView testoInglese;
    Button bottone;
    Thread myTimer;

    String primotestoitaliano ="";
    String titololezione;

    // voceAttiva=1 italiano  =2 inglese
    public int voceAttiva;

    VolumeExtractor volumeExtractor = new VolumeExtractor(this);
    DbAdapter dbHelper1;
    private MediaPlayer mp3Player;

    public String email;

    volatile boolean activityStopped = false;

    private Object mPauseLock;
    private boolean mPaused;
    private boolean mFinished;
    int flagPlayerStarted;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.singola_lezione);

        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        HashMap<String,String> user = new HashMap<String, String>();
        user = db.getUserDetails();
        email=user.get("email");

        // controllo i vari valori di bools.xml per bloccare il portrait mode nei telefonini
        if(getResources().getBoolean(R.bool.portrait_only)){
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);


            Display display = getWindowManager().getDefaultDisplay();
            int width = display.getWidth();  // deprecated
            int height = display.getHeight();  // deprecated
            Log.d("ORIENT","called orientation width="+width+" height="+height+" boolean="+getResources().getBoolean(R.bool.portrait_only));

        }

        // metto inglese come prima voce attiva perche' il primo cue point e' gia presente in schermo iniziale
        voceAttiva = 2;

        // inizializzo il sistema di controllo del thread
        mPauseLock = new Object();
        mPaused = false;
        mFinished = false;

        // ricevo idLezione da activity ListaLezione
        Intent i = getIntent();
        idLezione = i.getIntExtra("idlezione", 0);

        // leggo il titolo dal DB
        dbHelper1=new DbAdapter(this);
        titololezione=dbHelper1.getTitoloFromId(idLezione);

        final TextView titolo_italiano=(TextView) findViewById(R.id.titolo_lezione);
        titolo_italiano.setText(titololezione);

        // creo e rendo invisibile il tasto vai al quiz
        vaialquiz=(Button) findViewById(R.id.vaialtest);

        vaialquiz.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {

                Intent gotoquiz = new Intent(getApplicationContext(), Quiz.class);
                gotoquiz.putExtra("idlezione", idLezione);
                startActivity(gotoquiz);
            }
        });

        RelativeLayout rLayout = (RelativeLayout)findViewById(R.id.rlayout);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        RelativeLayout.LayoutParams lp2 = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);

        // TEXTVIEWS

        final TextView testoItaliano = (TextView) findViewById(R.id.testoinitaliano);
        testoItaliano.setText("-");

        final TextView testoInglese = (TextView) findViewById(R.id.testoininglese);
        testoInglese.setText("-");
    

        // BUTTONS
        cominciaLezione = (Button)findViewById(R.id.comincialalezione);
        cominciaLezione.setText("Comincia la Lezione");
        cominciaLezione.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {

                flagPlayerStarted=1;
                startLesson();
                cominciaLezione.setVisibility(View.GONE);
                vaialquiz.setVisibility(View.GONE);
                testoItaliano.setText(primotestoitaliano);
                testoInglese.setText("");
            }
        });

        fermaLezione = (Button)findViewById(R.id.fermalalezione);
        fermaLezione.setText("Pausa");
        fermaLezione.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                playpause();
            }
        });

        // INIZIALIZZO IL LETTORE MP3
        mp3Player = MediaPlayer.create(this, Uri.parse("http://www.okinglese.com/appAndroid/lezioni/" + idLezione + ".mp3"));


        // INIZIALIZZO LA GRAFICA

        itTeacher = (ImageView)findViewById(R.id.italianTeacher);
        itTeacher.setKeepScreenOn(true);

        enTeacher = (ImageView)findViewById(R.id.englishTeacher);
        enTeacher.setKeepScreenOn(true);


        // handler che riceve i dati dal THREAD timer che non e' dentro l'UI THREAD e non comunica con il layout
        handler = new Handler(){

            public void handleMessage(Message msg){

                if(msg.arg1!=3){
                    String msgReceived = (String) msg.obj;
                    if(msg.arg1==1)testoInglese.setText(msgReceived);
                    if(msg.arg1==2){
                        testoItaliano.setText(msgReceived);
                        testoInglese.setText("");
                    }
                    if(msg.arg2==1){
                        // il primotesto italiano deve diventare una variabile da poter raggiungere alla fine lezione
                        primotestoitaliano=msgReceived;
                    }
                    if(msg.arg2>10){

                        // devo fare il blinking degli occhi a seconda di quello che mi arriva di messaggio
                        // il numero che arriva segue formula:
                        // 10 x voceattiva + blinkingcountdown

                        int qualeteacherblink=(msg.arg2-msg.arg2%10)/10;
                        int valoreblinking=msg.arg2%10;

                        if(qualeteacherblink==1){
                            int idT = getResources().getIdentifier("com.gg.myapplication5.app:drawable/angelablink" + valoreblinking, null, null);
                            enTeacher.setImageResource(idT);

                        }
                        if(qualeteacherblink==2){
                            int idT = getResources().getIdentifier("com.gg.myapplication5.app:drawable/laurablink" + valoreblinking, null, null);
                            itTeacher.setImageResource(idT);

                        }

                    }
                } else {
                    // fine della lezione
                    Calendar c = Calendar.getInstance();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                    String strDate = sdf.format(c.getTime());
                    dbHelper1=new DbAdapter(getApplicationContext());
                    long idr= dbHelper1.insertVisitaLezione(idLezione,strDate);
                    voceAttiva = 2;
                    mp3Player.pause();
                    cominciaLezione.setVisibility(View.VISIBLE);
                    cominciaLezione.setText("Ripeti la lezione");
                    vaialquiz.setVisibility(View.VISIBLE);
                    indexcue=0;



                    // TODO: fare release() del volumeExtractor (o farlo quando si esce dall attivita)
                    // TODO: chiudere il mediaplayer
                }


            }
        };



        NetAsync();





    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        Toast.makeText(getApplicationContext(), "16. onDestroy()", Toast.LENGTH_SHORT).show();
        activityStopped = true;

    }


    @Override
    public void onBackPressed() {
        // your code.
        //Log.d("BACK","pressed back button");
    }

    @Override
    public void onPause() {
        super.onPause();
        if(flagPlayerStarted==1){
            mp3Player.pause();
            fermaLezione.setText("Play");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.lezione, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        DatabaseHandler db = new DatabaseHandler(getApplicationContext());
        HashMap<String,String> user = new HashMap<String, String>();
        user = db.getUserDetails();
        email=user.get("email");

        int id = item.getItemId();
        if (id == R.id.action_settings) {
            // vado al programma se utente e' loggato seno vado a login
            // vado a bacheca se utente e' loggato seno vado a login
            if(email != null){
                Intent gotomain = new Intent(getApplicationContext(), Main.class);
                startActivity(gotomain);

            }else{

                Intent gotobacheca = new Intent(getApplicationContext(), Login.class);
                startActivity(gotobacheca);

            }
            return true;
        }
        if (id == R.id.lezioni) {
            // vado a lista lezioni in OGNI CASO
            if(email != null){

                Intent gotomain = new Intent(getApplicationContext(), Lezioni.class);
                startActivity(gotomain);

            }else{
                Intent gotomain = new Intent(getApplicationContext(), Lezioni.class);
                startActivity(gotomain);

            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /**
     * Async Task to check whether internet connection is working.
     **/

    private class NetCheck extends AsyncTask<String,String,Boolean>
    {
        private ProgressDialog nDialog;
        private Context mContext;
        private int idLesson;

        public NetCheck(Context context, int idLezione) {
            mContext = context;
            idLesson=idLezione;
            //Log.d("CLASSE",""+mContext);
        }

        @Override
        protected void onPreExecute(){
            super.onPreExecute();
            nDialog = new ProgressDialog(MainActivity.this);
            nDialog.setTitle("Controllo connessione");
            nDialog.setMessage("Loading..");
            nDialog.setIndeterminate(false);
            nDialog.setCancelable(true);
            nDialog.show();
        }
        /**
         * Gets current device state and checks for working internet connection by trying Google.
         **/
        @Override
        protected Boolean doInBackground(String... args){



            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            if (netInfo != null && netInfo.isConnected()) {
                try {
                    //Log.d("NETWORK","TEST NETWORK");
                    URL url = new URL("http://www.google.com");
                    HttpURLConnection urlc = (HttpURLConnection) url.openConnection();
                    urlc.setConnectTimeout(3000);
                    urlc.connect();
                    if (urlc.getResponseCode() == 200) {
                        return true;
                    }
                } catch (MalformedURLException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            return false;

        }
        @Override
        protected void onPostExecute(Boolean th){

            if(th == true){
                nDialog.dismiss();
                //new ProcessLezioni().execute();
                CaricaCuePoints myCaricaCuePoints = new CaricaCuePoints(mContext,idLesson);
                myCaricaCuePoints.execute();
            }
            else{
                nDialog.dismiss();
                loginErrorMsg.setText("Error in Network Connection");
            }
        }

    }


    // classe CaricaCuePoints innerclass asynctask

    /**
     * Async Task to get and send data to My Sql database through JSON response. Carico lezioni
     **/
    private class CaricaCuePoints extends AsyncTask<String, Integer, String> {


        private ProgressDialog pDialog;
        public LinearLayout linearlayout;
        private Context mContext;
        private int idLesson;

        public CaricaCuePoints(Context context, int idLezione) {
            mContext = context;
            idLesson=idLezione;
        }


        @Override
        protected void onPreExecute() {
            super.onPreExecute();


            pDialog = new ProgressDialog(MainActivity.this);
            pDialog.setTitle("Caricamento lezione");
            pDialog.setMessage("Loading ...");
            pDialog.setIndeterminate(false);
            pDialog.setCancelable(true);
            pDialog.show();
        }

        @Override
        protected String doInBackground(String... args) {

            // carico la lista lezioni come string

            UserFunctions userFunction = new UserFunctions();
            String jString = userFunction.getCuePoints(String.valueOf(idLesson),email);
            // ritorno la string a onPostExecute


            return jString;
        }

        @Override
        protected void onPostExecute(String jString) {

            generaCuepoints(jString);
            pDialog.dismiss();


        }
    }

    public void NetAsync(){
        // devo passare il context THIS alle inner classes (netcheck e processlezioni)
        // in modo che possano accedere al Layout della classe esterna (Listalezioni)

        NetCheck task = new NetCheck(this, idLezione);
        task.execute();
    }

    public void generaCuepoints(String jString){



        try {
            jArray = new JSONArray(jString);
            JSONObject json_obj = jArray.getJSONObject(indexcue);
            primotestoitaliano=json_obj.getString("text");
            Message message=Message.obtain();
            message.arg1=voceAttiva;
            message.arg2=1;
            message.obj = json_obj.getString("text");
            handler.sendMessage(message);



        } catch (JSONException e) {
            Log.e("JSON Parser", "Error parsing data " + e.toString());
        }

    }

    public void switchVoceAttiva(){
        if(voceAttiva==1){
            voceAttiva=2;


        } else if(voceAttiva==2){
            voceAttiva=1;

        }

    }




    public void startLesson(){

        mp3Player.start();

        volumeExtractor.link(mp3Player);

        final int numeroCuepoints=jArray.length();




        Thread myTimer = new Thread(){
            int counter;
            public void run(){

                int blinkingcountdown=0;
                int duration = mp3Player.getDuration();
                short logoTimer=0;

                while (mp3Player.getCurrentPosition()<=duration && !mFinished ) {



                    try {
                            sleep (1000);
                            logoTimer+=1000;
                           
                            int currentPosition = mp3Player.getCurrentPosition();
                            
                            if(indexcue==(numeroCuepoints)){
                                //Log.d("FINE","FINE CUEPOINTS OK"); // CHIUDERE IL THREAD (opposto DI RUN)
                                Message message=Message.obtain();
                                message.arg1=3;
                                handler.sendMessage(message);
                                mp3Player.seekTo(0);
                                return;
                            }

                            try{
                                JSONObject json_obj = jArray.getJSONObject(indexcue);
                                if(indexcue<(numeroCuepoints-1))indexcuetext=indexcue+1;
                                JSONObject json_obj2 = jArray.getJSONObject(indexcuetext);

                                if(currentPosition>Integer.parseInt(json_obj.getString("time"))){


                                    switchVoceAttiva();
                                    Message message=Message.obtain();
                                    message.arg1=voceAttiva;
                                    message.arg2=2;
                                    if(indexcue<(numeroCuepoints-1))message.obj = json_obj2.getString("text");
                                    if(indexcue==(numeroCuepoints-1))message.obj = " ";
                                    handler.sendMessage(message);
                                    blinkingcountdown=2;

                                    if(indexcue<=numeroCuepoints)indexcue++;
                                }



                            } catch (JSONException e) {
                                Log.e("JSON Parser", "Error parsing data " + e.toString());
                            }

                            // se int blinkingcountdown; ha un valore devo fare un blink
                            if(blinkingcountdown>0){
                                // mando un messaggio a handler di cosa deve fare
                                Message message=Message.obtain();
                                // il numero da passare segue formula:
                                // 10 x voceattiva + blinkingcountdown
                                int argomentodapassare=voceAttiva*10+blinkingcountdown;
                                message.arg2=argomentodapassare;
                                handler.sendMessage(message);
                                //Log.d("BLINK","blink coundown="+blinkingcountdown);
                                blinkingcountdown--;
                            }
                            counter++;

                    } catch (InterruptedException e) {
                    }




                    synchronized (mPauseLock) {
                        while (mPaused) {
                            try {
                                mPauseLock.wait();
                            } catch (InterruptedException e) {
                            }
                        }
                    }

                }
            }

        };

        myTimer.start();

    }

    public void playpause(){
            if(mp3Player.isPlaying()) {
                mp3Player.pause();
                fermaLezione.setText("Play");

            } else {
                mp3Player.start();
                fermaLezione.setText("Pausa");

            }


    }






}

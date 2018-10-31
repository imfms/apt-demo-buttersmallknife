package ms.imf.buttersmallknife;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

import ms.imf.buttersmallknife.annotation.Bind;

public class MainActivity extends AppCompatActivity {

    @Bind(R.id.tvHelloWorld)
    public TextView tvHelloWorld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}

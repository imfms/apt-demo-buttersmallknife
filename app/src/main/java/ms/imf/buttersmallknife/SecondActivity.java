package ms.imf.buttersmallknife;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import ms.imf.buttersmallknife.annotation.Bind;

public class SecondActivity extends AppCompatActivity {

    @Bind(R.id.tvHelloWorld)
    TextView tvHelloWorld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterSmallKnife.bind(this, this.getWindow().getDecorView());

        tvHelloWorld.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(SecondActivity.this, "success", Toast.LENGTH_SHORT).show();
            }
        });
    }
}

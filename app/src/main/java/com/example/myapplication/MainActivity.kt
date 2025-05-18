package com.example.myapplication

// 다른 화면(FootActivity, AngleActivity)로 이동 하려고 쓸 Intent 가져오기
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(
                    onStartFootActivity = {
                        val intent = Intent(this, FootActivity::class.java)
                        startActivity(intent)
                    },
                    onStartAngleActivity = {
                        val intent = Intent(this, AngleActivity::class.java)
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    onStartFootActivity: () -> Unit,
    onStartAngleActivity: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = onStartFootActivity) {
                Text("족압 측정")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStartAngleActivity) {
                Text("발 각도")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        MainScreen(
            onStartFootActivity = {},
            onStartAngleActivity = {}
        )
    }
}

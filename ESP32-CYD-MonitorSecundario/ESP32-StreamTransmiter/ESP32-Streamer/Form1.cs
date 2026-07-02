namespace ESP32_Streamer
{
    public partial class Form1 : Form
    {
        StreamTransmiter streamTransmiter;

        public Form1()
        {
            streamTransmiter = new StreamTransmiter("192.168.15.2");
            InitializeComponent();
        }

        private async void button1_Click(object sender, EventArgs e)
        {
            await streamTransmiter.StartStreamAsync();
        }
    }
}

namespace esp32_cyd_desktop
{
    public partial class Form1 : Form
    {
        VuMeterStream vuMeterStream;
        private VuMeterControl vuControl;

        public Form1()
        {
            InitializeComponent();
            vuMeterStream = new VuMeterStream();
            vuControl = new VuMeterControl
            {
                Dock = DockStyle.Fill
            };
            this.Controls.Add(vuControl);
        }

        private void Form1_Load(object sender, EventArgs e)
        {
            vuMeterStream.OnAudioDataUpdated += (bands, mic) =>
            {
                // Garante a execução na Thread de UI
                if (this.IsHandleCreated)
                {
                    this.BeginInvoke((MethodInvoker)delegate
                    {
                        vuControl.UpdateValues(bands, mic);
                    });
                }
            };
        }
    }
}


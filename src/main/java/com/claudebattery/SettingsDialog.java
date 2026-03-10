package com.claudebattery;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

/**
 * Modal settings dialog.
 *
 * Two modes are available via a radio-button toggle:
 *   • Web Session — uses the claude.ai session cookie (no API credits needed)
 *   • API Key     — uses the Anthropic API key (requires API credits)
 */
public class SettingsDialog extends JDialog {

    private final ConfigManager      configManager;
    private final ClaudeUsageService usageService;

    // Mode
    private JRadioButton webModeRadio;
    private JRadioButton apiModeRadio;

    // Web session panel
    private JPasswordField sessionKeyField;
    private JLabel         sessionStatusLabel;

    // API key panel
    private JPasswordField apiKeyField;
    private JCheckBox      autoDetectCheck;
    private JLabel         apiStatusLabel;

    // Shared limits
    private JTextField hourlyLimitField;
    private JTextField weeklyLimitField;
    private JTextField pollIntervalField;
    private JCheckBox  notify80Check;
    private JCheckBox  notify95Check;

    // Card layout swap
    private JPanel authCards;
    private static final String CARD_WEB = "web";
    private static final String CARD_API = "api";

    public SettingsDialog(Frame parent, ConfigManager configManager, ClaudeUsageService usageService) {
        super(parent, "Claude Battery – Settings", true);
        this.configManager = configManager;
        this.usageService  = usageService;
        buildUI();
        loadValues();
        pack();
        setMinimumSize(new Dimension(500, 440));
        setLocationRelativeTo(parent);
    }

    /* ── UI ────────────────────────────────────────────────────────── */

    private void buildUI() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        // ── Mode selector ──────────────────────────────────────────
        JPanel modePanel = titledPanel("Connection Mode");
        modePanel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 4));
        webModeRadio = new JRadioButton("Web Session (claude.ai — no API credits needed)");
        apiModeRadio = new JRadioButton("API Key (requires separate API credits)");
        ButtonGroup bg = new ButtonGroup();
        bg.add(webModeRadio); bg.add(apiModeRadio);
        webModeRadio.addActionListener(e -> showCard(CARD_WEB));
        apiModeRadio.addActionListener(e -> showCard(CARD_API));
        modePanel.add(webModeRadio);
        modePanel.add(apiModeRadio);

        // ── Auth cards ─────────────────────────────────────────────
        authCards = new JPanel(new CardLayout());
        authCards.add(buildWebPanel(), CARD_WEB);
        authCards.add(buildApiPanel(), CARD_API);

        // ── Limits ─────────────────────────────────────────────────
        JPanel limitsPanel = titledPanel("Limits & Polling");
        limitsPanel.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4); c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        limitsPanel.add(new JLabel("Message / token limit (hourly):"), c);
        c.gridx = 1; c.weightx = 1;
        hourlyLimitField = new JTextField("40000", 8);
        limitsPanel.add(hourlyLimitField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        limitsPanel.add(new JLabel("Message / token limit (weekly):"), c);
        c.gridx = 1; c.weightx = 1;
        weeklyLimitField = new JTextField("1000000", 8);
        limitsPanel.add(weeklyLimitField, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        limitsPanel.add(new JLabel("Poll interval (seconds, min 30):"), c);
        c.gridx = 1; c.weightx = 1;
        pollIntervalField = new JTextField("300", 8);
        limitsPanel.add(pollIntervalField, c);

        // ── Notifications ──────────────────────────────────────────
        JPanel notifPanel = titledPanel("Notifications");
        notifPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 4));
        notify80Check = new JCheckBox("Warn at 80%");
        notify95Check = new JCheckBox("Alert at 95%");
        notifPanel.add(notify80Check);
        notifPanel.add(notify95Check);

        // ── Assembly ───────────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(modePanel);
        content.add(Box.createVerticalStrut(6));
        content.add(authCards);
        content.add(Box.createVerticalStrut(6));
        content.add(limitsPanel);
        content.add(Box.createVerticalStrut(6));
        content.add(notifPanel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        JButton save   = new JButton("Save");
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> save());
        getRootPane().setDefaultButton(save);
        buttons.add(cancel); buttons.add(save);

        root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel buildWebPanel() {
        JPanel p = titledPanel("claude.ai Session Key");
        p.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4); c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        p.add(new JLabel("Session Key:"), c);
        c.gridx = 1; c.weightx = 1;
        sessionKeyField = new JPasswordField(28);
        p.add(sessionKeyField, c);
        c.gridx = 2; c.weightx = 0;
        JButton testBtn = new JButton("Test");
        testBtn.addActionListener(e -> validateSessionKey());
        p.add(testBtn, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 3;
        sessionStatusLabel = new JLabel(" ");
        sessionStatusLabel.setFont(sessionStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        p.add(sessionStatusLabel, c);

        c.gridy = 2;
        JLabel hint = new JLabel(
                "<html><small>Get this from your browser:<br>" +
                "DevTools → Application → Cookies → claude.ai → <b>sessionKey</b></small></html>");
        hint.setForeground(Color.GRAY);
        p.add(hint, c);

        return p;
    }

    private JPanel buildApiPanel() {
        JPanel p = titledPanel("Anthropic API Key");
        p.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4); c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        p.add(new JLabel("API Key:"), c);
        c.gridx = 1; c.weightx = 1;
        apiKeyField = new JPasswordField(28);
        p.add(apiKeyField, c);
        c.gridx = 2; c.weightx = 0;
        JButton testBtn = new JButton("Test");
        testBtn.addActionListener(e -> validateApiKey());
        p.add(testBtn, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 3;
        apiStatusLabel = new JLabel(" ");
        apiStatusLabel.setFont(apiStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        p.add(apiStatusLabel, c);

        c.gridy = 2;
        autoDetectCheck = new JCheckBox("Auto-detect hourly token limit from API headers");
        p.add(autoDetectCheck, c);

        return p;
    }

    /* ── Card switch ────────────────────────────────────────────────── */
    private void showCard(String card) {
        ((CardLayout) authCards.getLayout()).show(authCards, card);
    }

    /* ── Load / Save ────────────────────────────────────────────────── */
    private void loadValues() {
        Config cfg = configManager.getConfig();

        if (cfg.useWebSession) { webModeRadio.setSelected(true); showCard(CARD_WEB); }
        else                   { apiModeRadio.setSelected(true); showCard(CARD_API); }

        sessionKeyField.setText(cfg.sessionKey);
        apiKeyField.setText(cfg.apiKey);
        autoDetectCheck.setSelected(cfg.autoDetectHourlyLimit);
        hourlyLimitField.setText(String.valueOf(cfg.hourlyTokenLimit));
        weeklyLimitField.setText(String.valueOf(cfg.weeklyTokenLimit));
        pollIntervalField.setText(String.valueOf(cfg.pollIntervalSeconds));
        notify80Check.setSelected(cfg.notifyAt80);
        notify95Check.setSelected(cfg.notifyAt95);
    }

    private void save() {
        Config cfg = configManager.getConfig();
        cfg.useWebSession = webModeRadio.isSelected();
        cfg.sessionKey    = new String(sessionKeyField.getPassword()).trim();
        cfg.apiKey        = new String(apiKeyField.getPassword()).trim();
        cfg.autoDetectHourlyLimit = autoDetectCheck.isSelected();

        try {
            cfg.hourlyTokenLimit = Long.parseLong(
                    hourlyLimitField.getText().trim().replace(",","").replace("_",""));
        } catch (NumberFormatException e) { err("Hourly limit must be a number."); return; }
        try {
            cfg.weeklyTokenLimit = Long.parseLong(
                    weeklyLimitField.getText().trim().replace(",","").replace("_",""));
        } catch (NumberFormatException e) { err("Weekly limit must be a number."); return; }
        try {
            cfg.pollIntervalSeconds = Integer.parseInt(pollIntervalField.getText().trim());
            if (cfg.pollIntervalSeconds < 30) { err("Poll interval must be ≥ 30 s."); return; }
        } catch (NumberFormatException e) { err("Poll interval must be a number."); return; }

        cfg.notifyAt80 = notify80Check.isSelected();
        cfg.notifyAt95 = notify95Check.isSelected();

        configManager.saveConfig(cfg);
        usageService.resetWebCache();  // re-fetch org ID if session key changed
        usageService.forceRefresh();
        dispose();
    }

    /* ── Validation ─────────────────────────────────────────────────── */
    private void validateSessionKey() {
        String key = new String(sessionKeyField.getPassword()).trim();
        if (key.isBlank()) { setWebStatus("Enter a session key first.", Color.ORANGE); return; }
        setWebStatus("Testing…", Color.GRAY);
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return new ClaudeWebClient().validateSessionKey(key);
            }
            @Override protected void done() {
                try {
                    if (get()) setWebStatus("✓ Connected to claude.ai", new Color(0, 140, 0));
                    else       setWebStatus("✗ Session key not accepted (try refreshing it)", Color.RED);
                } catch (Exception ex) { setWebStatus("Error: " + ex.getMessage(), Color.RED); }
            }
        }.execute();
    }

    private void validateApiKey() {
        String key = new String(apiKeyField.getPassword()).trim();
        if (key.isBlank()) { setApiStatus("Enter an API key first.", Color.ORANGE); return; }
        setApiStatus("Testing…", Color.GRAY);
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return new AnthropicClient().validateApiKey(key);
            }
            @Override protected void done() {
                try {
                    if (get()) setApiStatus("✓ Key is valid", new Color(0, 140, 0));
                    else       setApiStatus("✗ Key rejected (check console.anthropic.com)", Color.RED);
                } catch (Exception ex) { setApiStatus("Error: " + ex.getMessage(), Color.RED); }
            }
        }.execute();
    }

    private void setWebStatus(String msg, Color c) { sessionStatusLabel.setText(msg); sessionStatusLabel.setForeground(c); }
    private void setApiStatus(String msg, Color c)  { apiStatusLabel.setText(msg);     apiStatusLabel.setForeground(c); }

    private JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title,
                TitledBorder.LEFT, TitledBorder.TOP,
                p.getFont().deriveFont(Font.BOLD)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private void err(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
}

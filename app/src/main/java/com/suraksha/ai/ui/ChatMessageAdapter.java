package com.suraksha.ai.ui;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.suraksha.ai.R;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ChatMessageAdapter — renders user and agent message bubbles.
 *
 * TYPE_USER  → right-aligned blue bubble  (item_chat_user.xml)
 * TYPE_AGENT → left-aligned dark bubble   (item_chat_agent.xml)
 *
 * URLs in agent messages are automatically made clickable and open
 * in the browser/Maps app when tapped.
 */
public class ChatMessageAdapter extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<SurakshaAgentChatActivity.ChatMessage> messages;

    public ChatMessageAdapter(List<SurakshaAgentChatActivity.ChatMessage> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {

        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == SurakshaAgentChatActivity.ChatMessage.TYPE_USER) {
            View v = inflater.inflate(R.layout.item_chat_user, parent, false);
            return new UserViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_chat_agent, parent, false);
            return new AgentViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder, int position) {

        SurakshaAgentChatActivity.ChatMessage msg = messages.get(position);

        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg);
        } else {
            ((AgentViewHolder) holder).bind(msg);
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    // ── User bubble ViewHolder ────────────────────────────────────────────

    static class UserViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvMessage;

        UserViewHolder(View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tv_message);
        }

        void bind(SurakshaAgentChatActivity.ChatMessage msg) {
            tvMessage.setText(msg.text);
        }
    }

    // ── Agent bubble ViewHolder ───────────────────────────────────────────

    static class AgentViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvAgentLabel;
        private final TextView tvMessage;

        AgentViewHolder(View itemView) {
            super(itemView);
            tvAgentLabel = itemView.findViewById(R.id.tv_agent_label);
            tvMessage    = itemView.findViewById(R.id.tv_message);

            // Enable link clicking on agent messages
            tvMessage.setMovementMethod(LinkMovementMethod.getInstance());
        }

        void bind(SurakshaAgentChatActivity.ChatMessage msg) {
            tvAgentLabel.setText(msg.agentEmoji + "  " + msg.agentName);

            // Apply Linkify to make URLs, phone numbers clickable
            SpannableString spannable = new SpannableString(msg.text);
            Linkify.addLinks(spannable, Linkify.WEB_URLS | Linkify.PHONE_NUMBERS);

            // Also detect bare Maps coordinates like maps.google.com/?q=22.69,88.40
            linkifyGoogleMaps(spannable, msg.text);

            tvMessage.setText(spannable);
        }

        /**
         * Linkify bare Google Maps URLs that Linkify.WEB_URLS sometimes misses
         * because they contain coordinates with commas.
         * e.g. https://maps.google.com/?q=22.6985,88.4011
         */
        private void linkifyGoogleMaps(SpannableString spannable, String text) {
            Pattern pattern = Pattern.compile(
                    "https://maps\\.google\\.com/\\?q=[\\d.]+,[\\d.]+");
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String url   = matcher.group();
                int    start = matcher.start();
                int    end   = matcher.end();

                // Only add if Linkify hasn't already spanned this range
                URLSpan[] existing = spannable.getSpans(
                        start, end, URLSpan.class);
                if (existing == null || existing.length == 0) {
                    spannable.setSpan(new URLSpan(url),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }
}
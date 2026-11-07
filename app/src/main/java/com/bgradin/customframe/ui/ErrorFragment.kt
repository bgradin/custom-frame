package com.bgradin.customframe.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bgradin.customframe.databinding.FragmentErrorBinding

class ErrorFragment : Fragment() {
    private var _binding: FragmentErrorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentErrorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.getString("message")?.let { binding.errorText.text = it }
        binding.root.setBackgroundColor(Color.parseColor("#202124"))
    }

    fun setErrorMessage(msg: String) {
        arguments = (arguments ?: Bundle()).apply { putString("message", msg) }
        _binding?.errorText?.text = msg
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}